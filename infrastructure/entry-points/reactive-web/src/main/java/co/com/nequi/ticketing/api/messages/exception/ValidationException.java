package co.com.nequi.ticketing.api.messages.exception;

import lombok.Getter;

/**
 * Input of the web layer that does not meet its format rules. It is answered as {@code TKT0001}
 * and its message names the affected field or parameter.
 */
@Getter
public class ValidationException extends RuntimeException {

    private final String field;

    public ValidationException(String field, String detail) {
        super(field + ": " + detail);
        this.field = field;
    }
}
