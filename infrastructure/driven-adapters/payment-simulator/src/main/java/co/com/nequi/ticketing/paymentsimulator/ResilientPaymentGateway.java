package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR;

/**
 * Payment gateway behind a circuit breaker shared by charges and refunds. Only technical failures
 * count: a declined charge is an answer of the gateway. While the circuit is open no call reaches the
 * gateway and the error is the retryable {@code PAYMENT_GATEWAY_ERROR}, so the message returns to the
 * queue. Every change of state is logged and published as the gauge {@value #STATE_METRIC}:
 * 0 closed, 1 open, 2 half open.
 */
@Slf4j
public class ResilientPaymentGateway implements PaymentGateway {

    public static final String STATE_METRIC = "payment.gateway.circuit.state";
    private static final String CIRCUIT_NAME = "payment-gateway";

    private final PaymentGateway delegate;
    private final CircuitBreaker circuitBreaker;

    public ResilientPaymentGateway(PaymentGateway delegate, PaymentCircuitBreakerProperties properties,
                                   MeterRegistry meterRegistry, Clock clock) {
        this.delegate = delegate;
        this.circuitBreaker = CircuitBreaker.of(CIRCUIT_NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitInOpen())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenCalls())
                .recordException(TechnicalException.class::isInstance)
                .clock(clock)
                .build());
        this.circuitBreaker.getEventPublisher().onStateTransition(event -> log.warn(
                "Payment gateway circuit {}, from={}", event.getStateTransition().getToState(),
                event.getStateTransition().getFromState()));
        Gauge.builder(STATE_METRIC, circuitBreaker, breaker -> breaker.getState().getOrder())
                .description("State of the circuit breaker of the payment gateway: 0 closed, 1 open, 2 half open")
                .register(meterRegistry);
    }

    @Override
    public Mono<PaymentResult> charge(OrderId orderId) {
        return Mono.defer(() -> delegate.charge(orderId))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(CallNotPermittedException.class, PAYMENT_GATEWAY_ERROR::build);
    }

    @Override
    public Mono<Void> refund(OrderId orderId) {
        return Mono.defer(() -> delegate.refund(orderId))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(CallNotPermittedException.class, PAYMENT_GATEWAY_ERROR::build);
    }
}
