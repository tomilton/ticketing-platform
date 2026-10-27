package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ClockConfigTest {

    @Test
    void shouldReturnCurrentInstantWhenClockIsRead() {
        // Given
        ClockProvider clockProvider = new ClockConfig().clockProvider();
        Instant before = Instant.now();

        // When
        Instant now = clockProvider.now();

        // Then
        assertThat(now).isBetween(before, Instant.now());
    }
}
