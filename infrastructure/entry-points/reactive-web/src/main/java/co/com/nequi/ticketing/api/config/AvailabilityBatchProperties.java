package co.com.nequi.ticketing.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

/**
 * Limit of the batch availability, read from the {@code availability} block.
 *
 * @param batchMaxIds the largest number of ids of one request, at most {@value #MAX_ALLOWED_IDS}
 */
@ConfigurationProperties("availability")
public record AvailabilityBatchProperties(Integer batchMaxIds) {

    private static final int MIN_IDS = 1;
    private static final int MAX_ALLOWED_IDS = 100;

    public AvailabilityBatchProperties {
        Objects.requireNonNull(batchMaxIds, "Batch maximum ids must not be null");
        if (batchMaxIds < MIN_IDS || batchMaxIds > MAX_ALLOWED_IDS) {
            throw new IllegalArgumentException("Batch maximum ids must be between " + MIN_IDS + " and "
                    + MAX_ALLOWED_IDS);
        }
    }
}
