package co.com.nequi.ticketing.model.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSettingsTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration GRACE = Duration.ofSeconds(5);

    @Test
    void shouldKeepValuesWhenSettingsAreValid() {
        // When
        OrderSettings settings = new OrderSettings(6, 6, TTL, GRACE, 1);

        // Then
        assertThat(settings).isEqualTo(new OrderSettings(6, 6, Duration.ofMinutes(10), Duration.ofSeconds(5), 1));
    }

    @ParameterizedTest
    @CsvSource({"0, 6, 1", "7, 7, 1", "4, 3, 1", "4, 6, 0"})
    void shouldThrowIllegalArgumentExceptionWhenLimitsAreOutOfRange(Integer perOrder, Integer perCustomer,
                                                                    Integer concurrency) {
        // When / Then
        assertThatThrownBy(() -> new OrderSettings(perOrder, perCustomer, TTL, GRACE, concurrency))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"PT0S, PT5S", "PT10M, PT0S", "-PT1S, PT5S", "PT10M, -PT1S"})
    void shouldThrowIllegalArgumentExceptionWhenDurationIsNotPositive(Duration ttl, Duration grace) {
        // When / Then
        assertThatThrownBy(() -> new OrderSettings(6, 6, ttl, grace, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new OrderSettings(null, 6, TTL, GRACE, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderSettings(6, null, TTL, GRACE, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderSettings(6, 6, null, GRACE, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderSettings(6, 6, TTL, null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderSettings(6, 6, TTL, GRACE, null)).isInstanceOf(NullPointerException.class);
    }
}
