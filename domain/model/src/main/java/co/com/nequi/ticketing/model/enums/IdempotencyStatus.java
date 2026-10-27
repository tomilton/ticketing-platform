package co.com.nequi.ticketing.model.enums;

/**
 * Status of an idempotency key: claimed by a request in progress or closed with its outcome.
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
