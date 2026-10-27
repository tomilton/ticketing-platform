package co.com.nequi.ticketing.commons.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Catalog of technical errors with a stable code that is never reused. {@code retryable} tells the
 * worker whether a message that failed with this error can be processed again.
 */
@Getter
@RequiredArgsConstructor
public enum TechnicalErrorMessage {

    // Request
    VALIDATION_ERROR("TKT0001", "Error while validating data", Boolean.FALSE),
    INVALID_JSON("TKT0002", "Request body is not valid JSON", Boolean.FALSE),
    EMPTY_REQUEST_BODY("TKT0003", "Request body is empty", Boolean.FALSE),
    MISSING_IDEMPOTENCY_KEY("TKT0004", "Idempotency-Key header is missing or is not a UUID", Boolean.FALSE),

    // Infrastructure
    DYNAMODB_ERROR("TKT0005", "Database error", Boolean.TRUE),
    SQS_ERROR("TKT0006", "Message queue error", Boolean.TRUE),
    PAYMENT_GATEWAY_ERROR("TKT0007", "Payment gateway error", Boolean.TRUE),

    // Messages
    INVALID_MESSAGE("TKT0008", "Invalid queue message", Boolean.FALSE),

    // Other
    UNEXPECTED_ERROR("TKT0009", "Unexpected error", Boolean.TRUE);

    private final String code;
    private final String message;
    private final Boolean retryable;

    public TechnicalException build() {
        return new TechnicalException(this);
    }

    public TechnicalException build(Throwable cause) {
        return new TechnicalException(cause, this);
    }

    @Override
    public String toString() {
        return code + " - " + message;
    }
}
