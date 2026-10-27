package co.com.nequi.ticketing.model.config;

import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.util.Objects;

/**
 * Validation shared by the duration parameters.
 */
@UtilityClass
class DurationRules {

    void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
