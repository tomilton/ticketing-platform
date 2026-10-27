package co.com.nequi.ticketing.model.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Business parameters of the live availability subscription.
 *
 * @param pollInterval the time between two reads of the inventory; it must be positive
 */
public record AvailabilityWatchSettings(Duration pollInterval) {

    public AvailabilityWatchSettings {
        Objects.requireNonNull(pollInterval, "Poll interval must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("Poll interval must be positive");
        }
    }
}
