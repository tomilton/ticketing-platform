package co.com.nequi.ticketing.model.enums;

import co.com.nequi.ticketing.model.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Catalog of business errors with a stable code that is never reused.
 */
@Getter
@RequiredArgsConstructor
public enum BusinessErrorMessage {

    // Event catalog
    EVENT_NOT_FOUND("TKB0001", "Event not found"),
    EVENT_DATE_NOT_IN_FUTURE("TKB0010", "Event date must be in the future"),

    // Orders
    ORDER_NOT_FOUND("TKB0002", "Order not found"),
    TICKETS_SOLD_OUT("TKB0003", "Tickets sold out"),
    TICKET_LIMIT_EXCEEDED("TKB0004", "Ticket limit exceeded"),
    IDEMPOTENCY_KEY_REUSED("TKB0005", "Idempotency key reused with a different request"),
    REQUEST_IN_PROGRESS("TKB0006", "The original request is still in progress"),
    EVENT_NOT_ACTIVE("TKB0007", "Event is not on sale"),
    INVALID_STATE_TRANSITION("TKB0009", "Invalid order state transition");

    private final String code;
    private final String message;

    /**
     * Creates the business exception for this error.
     *
     * @return a new business exception
     */
    public BusinessException build() {
        return new BusinessException(this);
    }

    /**
     * Creates the business exception for this error with its cause.
     *
     * @param cause the original error
     * @return a new business exception
     */
    public BusinessException build(Throwable cause) {
        return new BusinessException(this, cause);
    }

    @Override
    public String toString() {
        return code + " - " + message;
    }
}
