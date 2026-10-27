package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorGatewayTest {

    private static final Duration LATENCY = Duration.ofMillis(200);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SLOW_LATENCY = Duration.ofSeconds(5);
    private static final Double HALF = 0.5;
    private static final Integer ORDERS = 200;

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

    @ParameterizedTest
    @CsvSource({"0.0, DECLINED", "1.0, APPROVED"})
    void shouldAnswerByApprovalRateWhenModeIsRandomAtTheBounds(Double approvalRate, PaymentResult expected) {
        // Given
        PaymentSimulatorGateway gateway = randomGateway(approvalRate);

        // When / Then
        StepVerifier.create(Flux.range(0, ORDERS).flatMap(index -> gateway.charge(OrderId.newId())).distinct())
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldRepeatDecisionWhenSameOrderIsChargedByAnotherInstance() {
        // Given
        List<OrderId> orders = Stream.generate(OrderId::newId).limit(ORDERS).toList();
        PaymentSimulatorGateway first = randomGateway(HALF);
        PaymentSimulatorGateway restarted = randomGateway(HALF);

        // When
        Mono<Boolean> repeated = Flux.fromIterable(orders)
                .flatMap(orderId -> Mono.zip(first.charge(orderId), restarted.charge(orderId)))
                .all(decisions -> decisions.getT1() == decisions.getT2());

        // Then
        StepVerifier.create(repeated)
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void shouldFailWithPaymentGatewayErrorWithoutChargingWhenLatencyExceedsTimeout() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.APPROVE, TIMEOUT.plusSeconds(1));
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.withVirtualTime(() -> gateway.charge(orderId))
                .thenAwait(TIMEOUT)
                .expectErrorSatisfies(PaymentSimulatorGatewayTest::assertPaymentGatewayError)
                .verify();
        assertThat(gateway.countCharges()).isZero();
    }

    @Test
    void shouldFailWithPaymentGatewayErrorWithoutChargingWhenModeIsSlow() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.SLOW, LATENCY);
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.withVirtualTime(() -> gateway.charge(orderId))
                .expectSubscription()
                .expectNoEvent(TIMEOUT.minusMillis(1))
                .thenAwait(Duration.ofMillis(1))
                .expectErrorSatisfies(PaymentSimulatorGatewayTest::assertPaymentGatewayError)
                .verify();
        assertThat(gateway.countCharges()).isZero();
    }

    @Test
    void shouldFailWithPaymentGatewayErrorWithoutChargingWhenModeIsDown() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.DOWN, LATENCY);
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.withVirtualTime(() -> gateway.charge(orderId))
                .expectSubscription()
                .expectNoEvent(LATENCY.minusMillis(1))
                .thenAwait(Duration.ofMillis(1))
                .expectErrorSatisfies(PaymentSimulatorGatewayTest::assertPaymentGatewayError)
                .verify();
        assertThat(gateway.countCharges()).isZero();
    }

    @Test
    void shouldFailWithUnexpectedErrorWhenOrderIsMissing() {
        // Given
        PaymentSimulatorGateway gateway = gateway(Mode.APPROVE, Duration.ZERO);

        // When / Then
        StepVerifier.create(gateway.charge(null))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(TechnicalException.class)
                        .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.UNEXPECTED_ERROR))
                .verify();
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

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldHaveNoEffectWhenOrderWithoutChargeIsRefundedTwiceInAnyMode(Mode mode) {
        // Given
        PaymentSimulatorGateway gateway = gateway(mode, Duration.ZERO);
        OrderId orderId = OrderId.newId();

        // When / Then
        StepVerifier.create(gateway.refund(orderId).then(gateway.refund(orderId)))
                .verifyComplete();
        assertThat(gateway.isRefunded(orderId)).isFalse();
        assertThat(gateway.isCharged(orderId)).isFalse();
    }

    private static void assertPaymentGatewayError(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR);
    }

    private static PaymentSimulatorGateway randomGateway(Double approvalRate) {
        return new PaymentSimulatorGateway(new PaymentSimulatorProperties(Mode.RANDOM, Duration.ZERO, TIMEOUT,
                approvalRate, SLOW_LATENCY));
    }

    private static PaymentSimulatorGateway gateway(Mode mode, Duration latency) {
        return new PaymentSimulatorGateway(new PaymentSimulatorProperties(mode, latency, TIMEOUT, HALF,
                SLOW_LATENCY));
    }
}
