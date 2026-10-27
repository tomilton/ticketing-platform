package co.com.nequi.ticketing.dynamodb.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Settings of the DynamoDB adapter, read from the {@code adapters.dynamodb} block.
 *
 * @param region                  the AWS region
 * @param endpoint                the endpoint override, only for the local environment; {@code null} otherwise
 * @param eventsTable             the table of events
 * @param inventoryTable          the table of inventory shards
 * @param eventsByStatusDateIndex the index of events by status and date
 * @param apiCallTimeout          the maximum time of a call, including attempts
 * @param apiCallAttemptTimeout   the maximum time of one attempt
 * @param batchConcurrency        the groups of keys of a batch read in flight at the same time
 * @param healthCheckTimeout      the maximum time of the health check
 */
@Validated
@ConfigurationProperties("adapters.dynamodb")
public record DynamoDbProperties(@NotBlank String region,
                                 String endpoint,
                                 @NotBlank String eventsTable,
                                 @NotBlank String inventoryTable,
                                 @NotBlank String eventsByStatusDateIndex,
                                 Duration apiCallTimeout,
                                 Duration apiCallAttemptTimeout,
                                 Integer batchConcurrency,
                                 Duration healthCheckTimeout) {

    private static final int MIN_BATCH_CONCURRENCY = 1;

    public DynamoDbProperties {
        Objects.requireNonNull(batchConcurrency, "Batch concurrency must not be null");
        if (batchConcurrency < MIN_BATCH_CONCURRENCY) {
            throw new IllegalArgumentException("Batch concurrency must be at least " + MIN_BATCH_CONCURRENCY);
        }
        Stream.of(apiCallTimeout, apiCallAttemptTimeout, healthCheckTimeout).forEach(DynamoDbProperties::requirePositive);
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "Timeouts must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
    }
}
