package co.com.nequi.ticketing.paymentsimulator;

import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentSimulatorPropertiesTest {

    private static final Duration LATENCY = Duration.ofMillis(200);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SLOW_LATENCY = Duration.ofSeconds(5);
    private static final Double HALF = 0.5;

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    void shouldCreatePropertiesWhenApprovalRateIsWithinBounds(Double approvalRate) {
        // When
        PaymentSimulatorProperties properties = new PaymentSimulatorProperties(Mode.RANDOM, LATENCY, TIMEOUT,
                approvalRate, SLOW_LATENCY);

        // Then
        assertThat(properties.approvalRate()).isEqualTo(approvalRate);
        assertThat(properties.slowLatency()).isEqualTo(SLOW_LATENCY);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01})
    void shouldRejectPropertiesWhenApprovalRateIsOutOfBounds(Double approvalRate) {
        // When / Then
        assertThatThrownBy(() -> new PaymentSimulatorProperties(Mode.RANDOM, LATENCY, TIMEOUT, approvalRate,
                SLOW_LATENCY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Approval rate must be between 0 and 1");
    }

    @ParameterizedTest
    @ValueSource(longs = {1000L, 2000L})
    void shouldRejectPropertiesWhenSlowLatencyDoesNotExceedTimeout(Long slowLatencyMillis) {
        // Given
        Duration slowLatency = Duration.ofMillis(slowLatencyMillis);

        // When / Then
        assertThatThrownBy(() -> new PaymentSimulatorProperties(Mode.SLOW, LATENCY, TIMEOUT, HALF, slowLatency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Slow latency must be greater than the timeout");
    }

    @Test
    void shouldRejectPropertiesWhenLatencyIsNegativeOrTimeoutIsNotPositive() {
        // Given
        Duration negative = Duration.ofMillis(-1);

        // When / Then
        assertThatThrownBy(() -> new PaymentSimulatorProperties(Mode.APPROVE, negative, TIMEOUT, HALF, SLOW_LATENCY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentSimulatorProperties(Mode.APPROVE, LATENCY, Duration.ZERO, HALF,
                SLOW_LATENCY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectPropertiesWhenAnyValueIsMissing() {
        // When / Then
        assertThatThrownBy(() -> new PaymentSimulatorProperties(null, LATENCY, TIMEOUT, HALF, SLOW_LATENCY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentSimulatorProperties(Mode.RANDOM, LATENCY, TIMEOUT, null, SLOW_LATENCY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentSimulatorProperties(Mode.SLOW, LATENCY, TIMEOUT, HALF, null))
                .isInstanceOf(NullPointerException.class);
    }
}
