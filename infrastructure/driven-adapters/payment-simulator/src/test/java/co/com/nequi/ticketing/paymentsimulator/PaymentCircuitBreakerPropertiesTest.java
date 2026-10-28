package co.com.nequi.ticketing.paymentsimulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCircuitBreakerPropertiesTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void shouldKeepValuesWhenSettingsAreValid() {
        // When
        PaymentCircuitBreakerProperties properties = new PaymentCircuitBreakerProperties(50.0F, 20, 10, WAIT, 3);

        // Then
        assertThat(properties).usingRecursiveComparison()
                .isEqualTo(new PaymentCircuitBreakerProperties(50.0F, 20, 10, Duration.ofSeconds(30), 3));
    }

    @ParameterizedTest
    @CsvSource({"0, 20, 10, 3", "101, 20, 10, 3", "50, 0, 10, 3", "50, 20, 0, 3", "50, 20, 10, 0"})
    void shouldThrowIllegalArgumentExceptionWhenSettingIsOutOfRange(Float threshold, Integer window, Integer minimum,
                                                                   Integer halfOpen) {
        // When / Then
        assertThatThrownBy(() -> new PaymentCircuitBreakerProperties(threshold, window, minimum, WAIT, halfOpen))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenWaitIsNotPositive() {
        // When / Then
        assertThatThrownBy(() -> new PaymentCircuitBreakerProperties(50.0F, 20, 10, Duration.ZERO, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenSettingIsNull() {
        // When / Then
        assertThatThrownBy(() -> new PaymentCircuitBreakerProperties(null, 20, 10, WAIT, 3))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentCircuitBreakerProperties(50.0F, null, 10, WAIT, 3))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentCircuitBreakerProperties(50.0F, 20, null, WAIT, 3))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentCircuitBreakerProperties(50.0F, 20, 10, null, 3))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentCircuitBreakerProperties(50.0F, 20, 10, WAIT, null))
                .isInstanceOf(NullPointerException.class);
    }
}
