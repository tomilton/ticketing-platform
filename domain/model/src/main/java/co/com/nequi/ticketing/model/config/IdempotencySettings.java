package co.com.nequi.ticketing.model.config;

import java.time.Duration;

/**
 * Business parameters of the idempotency keys.
 *
 * @param ttl   how long a key is kept; it must be positive
 * @param lease how long a request holds a key before another one can take it; positive and shorter than {@code ttl}
 */
public record IdempotencySettings(Duration ttl, Duration lease) {

    public IdempotencySettings {
        DurationRules.requirePositive(ttl, "Idempotency TTL");
        DurationRules.requirePositive(lease, "Idempotency lease");
        if (lease.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("Idempotency lease must be shorter than its TTL");
        }
    }
}
