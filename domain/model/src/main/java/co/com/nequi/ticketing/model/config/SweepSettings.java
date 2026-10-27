package co.com.nequi.ticketing.model.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Business parameters of the sweep that releases the expired reservations.
 *
 * @param lockLease   how long a replica holds the sweep before another one can take it; it must be positive
 * @param batchSize   the expired orders released in one cycle, at least {@value #MIN_VALUE}
 * @param concurrency the orders released at the same time, at least {@value #MIN_VALUE}
 */
public record SweepSettings(Duration lockLease, Integer batchSize, Integer concurrency) {

    public static final int MIN_VALUE = 1;

    public SweepSettings {
        DurationRules.requirePositive(lockLease, "Sweep lock lease");
        Objects.requireNonNull(batchSize, "Sweep batch size must not be null");
        Objects.requireNonNull(concurrency, "Sweep concurrency must not be null");
        if (batchSize < MIN_VALUE || concurrency < MIN_VALUE) {
            throw new IllegalArgumentException("Sweep batch size and concurrency must be at least " + MIN_VALUE);
        }
    }
}
