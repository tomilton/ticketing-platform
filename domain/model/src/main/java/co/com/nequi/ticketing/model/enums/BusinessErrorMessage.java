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
    EVENT_DATE_NOT_IN_FUTURE("TKB0010", "Event date must be in the future");

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
