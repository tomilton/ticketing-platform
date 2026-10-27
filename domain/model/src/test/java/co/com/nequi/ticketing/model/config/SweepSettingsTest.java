package co.com.nequi.ticketing.model.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SweepSettingsTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Test
    void shouldKeepValuesWhenSettingsAreValid() {
        // When
        SweepSettings settings = new SweepSettings(LEASE, 1, 1);

        // Then
        assertThat(settings).isEqualTo(new SweepSettings(Duration.ofSeconds(60), 1, 1));
    }

    @ParameterizedTest
    @CsvSource({"PT60S, 0, 1", "PT60S, 1, 0", "PT0S, 1, 1", "-PT1S, 1, 1"})
    void shouldThrowIllegalArgumentExceptionWhenValueIsOutOfRange(Duration lease, Integer batchSize,
                                                                  Integer concurrency) {
        // When / Then
        assertThatThrownBy(() -> new SweepSettings(lease, batchSize, concurrency))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new SweepSettings(null, 1, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SweepSettings(LEASE, null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SweepSettings(LEASE, 1, null)).isInstanceOf(NullPointerException.class);
    }
}
