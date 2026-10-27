package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties.Mode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.UNEXPECTED_ERROR;

/**
 * Test double of an external payment gateway, idempotent by order: the same order always gets the
 * same answer and counts one charge. In {@link Mode#RANDOM} the answer depends only on the order
 * identifier, so it is the same after a retry or a restart. Charges and refunds live in the memory
 * of the process, so the charge count only holds within one instance, as a real gateway would
 * provide on its side. Refunds behave the same in every mode.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSimulatorGateway implements PaymentGateway {

    private static final Long RATE_SCALE = 10_000L;

    private final PaymentSimulatorProperties properties;
    private final Map<OrderId, PaymentResult> charges = new ConcurrentHashMap<>();
    private final Set<OrderId> refunds = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<PaymentResult> charge(OrderId orderId) {
        return Mono.delay(chargeDelay())
                .doOnNext(elapsed -> log.debug("Payment charge, gateway=simulator, mode={}, orderId={}",
                        properties.mode(), orderId.value()))
                .flatMap(elapsed -> answer(orderId))
                .timeout(properties.timeout())
                .onErrorMap(PaymentSimulatorGateway::toTechnicalException);
    }

    @Override
    public Mono<Void> refund(OrderId orderId) {
        return Mono.delay(properties.latency())
                .doOnNext(elapsed -> log.debug("Payment refund, gateway=simulator, orderId={}", orderId.value()))
                .filter(elapsed -> isCharged(orderId))
                .doOnNext(elapsed -> refunds.add(orderId))
                .timeout(properties.timeout())
                .then()
                .onErrorMap(PaymentSimulatorGateway::toTechnicalException);
    }

    /**
     * Tells whether an order was charged and approved.
     *
     * @param orderId the order
     * @return {@code true} if the simulator approved a charge of the order
     */
    public boolean isCharged(OrderId orderId) {
        return charges.get(orderId) == PaymentResult.APPROVED;
    }

    /**
     * Tells whether the charge of an order was refunded.
     *
     * @param orderId the order
     * @return {@code true} if the approved charge of the order was refunded
     */
    public boolean isRefunded(OrderId orderId) {
        return refunds.contains(orderId);
    }

    /**
     * Counts the orders with a registered charge, approved or declined.
     *
     * @return the number of charged orders
     */
    public Integer countCharges() {
        return charges.size();
    }

    private Duration chargeDelay() {
        return properties.mode() == Mode.SLOW ? properties.slowLatency() : properties.latency();
    }

    private Mono<PaymentResult> answer(OrderId orderId) {
        return switch (properties.mode()) {
            case APPROVE -> register(orderId, PaymentResult.APPROVED);
            case DECLINE -> register(orderId, PaymentResult.DECLINED);
            case RANDOM -> register(orderId, decide(orderId));
            case SLOW, DOWN -> Mono.defer(() -> Mono.error(PAYMENT_GATEWAY_ERROR.build()));
        };
    }

    private Mono<PaymentResult> register(OrderId orderId, PaymentResult result) {
        return Mono.fromSupplier(() -> charges.computeIfAbsent(orderId, id -> result));
    }

    private PaymentResult decide(OrderId orderId) {
        Long bucket = Math.floorMod(orderId.value().getLeastSignificantBits(), RATE_SCALE);
        return bucket < properties.approvalRate() * RATE_SCALE ? PaymentResult.APPROVED : PaymentResult.DECLINED;
    }

    private static TechnicalException toTechnicalException(Throwable error) {
        return switch (error) {
            case TechnicalException technicalException -> technicalException;
            case TimeoutException timeout -> PAYMENT_GATEWAY_ERROR.build(timeout);
            default -> UNEXPECTED_ERROR.build(error);
        };
    }
}
