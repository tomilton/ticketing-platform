package co.com.nequi.ticketing.commons.exception;

import lombok.Getter;

/**
 * Error raised by an entry point or a driven adapter when a technical operation fails.
 */
@Getter
public class TechnicalException extends RuntimeException {

    private final TechnicalErrorMessage errorMessage;

    public TechnicalException(TechnicalErrorMessage errorMessage) {
        super(errorMessage.getMessage());
        this.errorMessage = errorMessage;
    }

    public TechnicalException(Throwable cause, TechnicalErrorMessage errorMessage) {
        super(errorMessage.getMessage(), cause);
        this.errorMessage = errorMessage;
    }
}
