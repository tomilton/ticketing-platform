package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR;

/**
 * Test double of an external payment gateway, idempotent by order: the same order always gets the
 * same answer and counts one charge. Charges and refunds live in the memory of the process, so the
 * idempotency only holds within one instance, as a real gateway would provide on its side.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSimulatorGateway implements PaymentGateway {

    private final PaymentSimulatorProperties properties;
    private final Map<OrderId, PaymentResult> charges = new ConcurrentHashMap<>();
    private final Set<OrderId> refunds = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<PaymentResult> charge(OrderId orderId) {
        return Mono.delay(properties.latency())
                .map(elapsed -> {
                    log.debug("Payment charge, gateway=simulator, orderId={}", orderId.value());
                    return charges.computeIfAbsent(orderId, id -> answer());
                })
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

    private PaymentResult answer() {
        return properties.mode() == PaymentSimulatorProperties.Mode.APPROVE ? PaymentResult.APPROVED
                : PaymentResult.DECLINED;
    }

    private static TechnicalException toTechnicalException(Throwable error) {
        return error instanceof TechnicalException technicalException
                ? technicalException
                : PAYMENT_GATEWAY_ERROR.build(error);
    }
}
