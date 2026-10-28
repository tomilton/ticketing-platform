package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResilientPaymentGatewayTest {

    private static final Integer WINDOW = 4;
    private static final Duration WAIT_IN_OPEN = Duration.ofSeconds(30);
    private static final Double CLOSED = 0.0;
    private static final Double OPEN = 1.0;
    private static final Double HALF_OPEN = 2.0;

    @Mock
    private PaymentGateway delegate;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-27T10:00:00Z"));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ResilientPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new ResilientPaymentGateway(delegate,
                new PaymentCircuitBreakerProperties(50.0F, WINDOW, WINDOW, WAIT_IN_OPEN, 2), meterRegistry, clock);
    }

    @Test
    void shouldOpenAndRejectWithoutCallingGatewayWhenFailuresReachThreshold() {
        // Given
        when(delegate.charge(any(OrderId.class))).thenReturn(Mono.error(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR.build()));
        failCharges(WINDOW);

        // When / Then
        StepVerifier.create(gateway.charge(OrderId.newId()))
                .expectErrorSatisfies(ResilientPaymentGatewayTest::assertRetryableGatewayError)
                .verify();
        verify(delegate, times(WINDOW)).charge(any(OrderId.class));
        assertThat(state()).isEqualTo(OPEN);
    }

    @Test
    void shouldStayClosedWhenGatewayDeclinesCharges() {
        // Given
        when(delegate.charge(any(OrderId.class))).thenReturn(Mono.just(PaymentResult.DECLINED));

        // When
        StepVerifier.create(Flux.range(0, WINDOW * 2).concatMap(index -> gateway.charge(OrderId.newId())))
                .expectNextCount(WINDOW * 2L)
                .verifyComplete();

        // Then
        assertThat(state()).isEqualTo(CLOSED);
    }

    @Test
    void shouldLetTestCallsThroughAndCloseWhenWaitEndsAndGatewayRecovers() {
        // Given
        when(delegate.charge(any(OrderId.class)))
                .thenReturn(Mono.error(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR.build()),
                        Mono.error(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR.build()),
                        Mono.error(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR.build()),
                        Mono.error(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR.build()),
                        Mono.just(PaymentResult.APPROVED));
        failCharges(WINDOW);
        clock.advance(WAIT_IN_OPEN.plusSeconds(1));

        // When
        StepVerifier.create(gateway.charge(OrderId.newId()))
                .expectNext(PaymentResult.APPROVED)
                .verifyComplete();
        assertThat(state()).isEqualTo(HALF_OPEN);
        StepVerifier.create(gateway.charge(OrderId.newId()))
                .expectNext(PaymentResult.APPROVED)
                .verifyComplete();

        // Then
        assertThat(state()).isEqualTo(CLOSED);
    }

    @Test
    void shouldRejectChargeWithoutCallingGatewayWhenRefundsOpenedCircuit() {
        // Given
        when(delegate.refund(any(OrderId.class))).thenReturn(Mono.error(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR.build()));
        StepVerifier.create(Flux.range(0, WINDOW).concatMap(index -> gateway.refund(OrderId.newId())
                        .onErrorResume(TechnicalException.class, error -> Mono.empty())))
                .verifyComplete();

        // When / Then
        StepVerifier.create(gateway.charge(OrderId.newId()))
                .expectErrorSatisfies(ResilientPaymentGatewayTest::assertRetryableGatewayError)
                .verify();
        StepVerifier.create(gateway.refund(OrderId.newId()))
                .expectErrorSatisfies(ResilientPaymentGatewayTest::assertRetryableGatewayError)
                .verify();
        verify(delegate, times(WINDOW)).refund(any(OrderId.class));
    }

    private void failCharges(Integer count) {
        StepVerifier.create(Flux.range(0, count).concatMap(index -> gateway.charge(OrderId.newId())
                        .onErrorResume(TechnicalException.class, error -> Mono.empty())))
                .verifyComplete();
    }

    private Double state() {
        return meterRegistry.get(ResilientPaymentGateway.STATE_METRIC).gauge().value();
    }

    private static void assertRetryableGatewayError(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class);
        TechnicalException technical = (TechnicalException) error;
        assertThat(technical.getErrorMessage()).isEqualTo(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR);
        assertThat(technical.getErrorMessage().getRetryable()).isTrue();
    }

    private static final class ManualClock extends Clock {

        private Instant now;

        private ManualClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
