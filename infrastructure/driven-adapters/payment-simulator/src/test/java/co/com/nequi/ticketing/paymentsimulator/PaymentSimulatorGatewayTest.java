package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorGatewayTest {

    private static final Duration LATENCY = Duration.ofMillis(200);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SLOW_LATENCY = Duration.ofSeconds(5);
    private static final Double HALF = 0.5;

    @ParameterizedTest
    @CsvSource({"APPROVE, APPROVED", "DECLINE, DECLINED"})
    void shouldAnswerByModeAfterLatencyWhenOrderIsCharged(Mode mode, PaymentResult expected) {
        // Given
        PaymentSimulatorGateway gateway = gateway(mode, LATENCY);
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.withVirtualTime(() -> gateway.charge(orderId))
                .expectSubscription()
                .expectNoEvent(LATENCY.minusMillis(1))
                .thenAwait(Duration.ofMillis(1))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldReturnSameAnswerAndCountOneChargeWhenSameOrderIsChargedTwice() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.APPROVE, Duration.ZERO);
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.create(gateway.charge(orderId).then(gateway.charge(orderId)))
                .expectNext(PaymentResult.APPROVED)
                .verifyComplete();
        assertThat(gateway.countCharges()).isEqualTo(1);
        assertThat(gateway.isCharged(orderId)).isTrue();
    }

    @Test
    void shouldFailWithPaymentGatewayErrorWithoutChargingWhenLatencyExceedsTimeout() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.APPROVE, TIMEOUT.plusSeconds(1));
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.withVirtualTime(() -> gateway.charge(orderId))
                .thenAwait(TIMEOUT)
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(TechnicalException.class)
                        .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR))
                .verify();
        assertThat(gateway.countCharges()).isZero();
    }

    @Test
    void shouldRegisterRefundOnceWhenApprovedOrderIsRefundedTwice() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.APPROVE, Duration.ZERO);
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.create(gateway.charge(orderId).then(gateway.refund(orderId)).then(gateway.refund(orderId)))
                .verifyComplete();
        assertThat(gateway.isRefunded(orderId)).isTrue();
    }

    @Test
    void shouldHaveNoEffectWhenOrderWithoutChargeIsRefunded() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.APPROVE, Duration.ZERO);
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.create(gateway.refund(orderId))
                .verifyComplete();
        assertThat(gateway.isRefunded(orderId)).isFalse();
        assertThat(gateway.isCharged(orderId)).isFalse();
    }

    private static PaymentSimulatorGateway gateway(Mode mode, Duration latency) {
        return new PaymentSimulatorGateway(new PaymentSimulatorProperties(mode, latency, TIMEOUT, HALF,
                SLOW_LATENCY));
    }
}
