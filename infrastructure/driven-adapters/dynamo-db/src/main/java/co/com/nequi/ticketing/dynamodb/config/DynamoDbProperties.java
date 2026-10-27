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
 * @param region                     the AWS region
 * @param endpoint                   the endpoint override, only for the local environment; {@code null} otherwise
 * @param eventsTable                the table of events
 * @param inventoryTable             the table of inventory shards
 * @param eventsByStatusDateIndex    the index of events by status and date
 * @param apiCallTimeout             the maximum time of a call, including attempts
 * @param apiCallAttemptTimeout      the maximum time of one attempt
 * @param batchConcurrency           the groups of keys of a batch read in flight at the same time
 * @param healthCheckTimeout         the maximum time of the health check
 * @param ordersTable                the table of orders
 * @param orderAuditTable            the table of the audit trail of the orders
 * @param idempotencyTable           the table of idempotency keys
 * @param customerLimitsTable        the table of tickets held by customer and event
 * @param expiringReservationsIndex  the index of active orders by expiration
 * @param pendingPublicationIndex    the index of orders not yet published
 * @param transactionConflictRetries the retries of a reservation cancelled by a transaction conflict
 * @param reserveTimeout             the maximum time of a whole reservation, including shards and retries
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
                                 Duration healthCheckTimeout,
                                 @NotBlank String ordersTable,
                                 @NotBlank String orderAuditTable,
                                 @NotBlank String idempotencyTable,
                                 @NotBlank String customerLimitsTable,
                                 @NotBlank String expiringReservationsIndex,
                                 @NotBlank String pendingPublicationIndex,
                                 Integer transactionConflictRetries,
                                 Duration reserveTimeout) {

    private static final int MIN_BATCH_CONCURRENCY = 1;
    private static final int MIN_CONFLICT_RETRIES = 1;

    public DynamoDbProperties {
        Objects.requireNonNull(batchConcurrency, "Batch concurrency must not be null");
        Objects.requireNonNull(transactionConflictRetries, "Transaction conflict retries must not be null");
        if (batchConcurrency < MIN_BATCH_CONCURRENCY) {
            throw new IllegalArgumentException("Batch concurrency must be at least " + MIN_BATCH_CONCURRENCY);
        }
        if (transactionConflictRetries < MIN_CONFLICT_RETRIES) {
            throw new IllegalArgumentException("Transaction conflict retries must be at least " + MIN_CONFLICT_RETRIES);
        }
        Stream.of(apiCallTimeout, apiCallAttemptTimeout, healthCheckTimeout, reserveTimeout)
                .forEach(DynamoDbProperties::requirePositive);
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "Timeouts must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
    }
}
