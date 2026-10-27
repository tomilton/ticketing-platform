package co.com.nequi.ticketing.model.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencySettingsTest {

    @Test
    void shouldKeepValuesWhenLeaseIsShorterThanTtl() {
        // When
        IdempotencySettings settings = new IdempotencySettings(Duration.ofHours(24), Duration.ofSeconds(10));

        // Then
        assertThat(settings.ttl()).isEqualTo(Duration.ofHours(24));
        assertThat(settings.lease()).isEqualTo(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @CsvSource({"PT0S, PT0S", "PT24H, PT0S", "PT10S, PT10S", "PT10S, PT11S"})
    void shouldThrowIllegalArgumentExceptionWhenDurationsAreInvalid(Duration ttl, Duration lease) {
        // When / Then
        assertThatThrownBy(() -> new IdempotencySettings(ttl, lease)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // Given
        Duration duration = Duration.ofSeconds(10);

        // When / Then
        assertThatThrownBy(() -> new IdempotencySettings(null, duration)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IdempotencySettings(duration, null)).isInstanceOf(NullPointerException.class);
    }
}
