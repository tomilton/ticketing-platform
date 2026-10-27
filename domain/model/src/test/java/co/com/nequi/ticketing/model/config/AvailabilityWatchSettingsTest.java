package co.com.nequi.ticketing.model.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvailabilityWatchSettingsTest {

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void shouldThrowIllegalArgumentExceptionWhenPollIntervalIsNotPositive(String pollInterval) {
        // Given
        Duration interval = Duration.parse(pollInterval);

        // When / Then
        assertThatThrownBy(() -> new AvailabilityWatchSettings(interval)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenPollIntervalIsNull() {
        // When / Then
        assertThatThrownBy(() -> new AvailabilityWatchSettings(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldKeepPollIntervalWhenPollIntervalIsPositive() {
        // When / Then
        assertThat(new AvailabilityWatchSettings(Duration.ofSeconds(1)).pollInterval())
                .isEqualTo(Duration.ofSeconds(1));
    }
}
