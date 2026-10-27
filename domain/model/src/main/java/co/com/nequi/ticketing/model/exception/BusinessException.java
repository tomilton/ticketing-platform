package co.com.nequi.ticketing.model.exception;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import lombok.Getter;

/**
 * Error raised when a business rule is not met.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final BusinessErrorMessage businessErrorMessage;

    public BusinessException(BusinessErrorMessage businessErrorMessage) {
        super(businessErrorMessage.getMessage());
        this.businessErrorMessage = businessErrorMessage;
    }

    public BusinessException(BusinessErrorMessage businessErrorMessage, Throwable cause) {
        super(businessErrorMessage.getMessage(), cause);
        this.businessErrorMessage = businessErrorMessage;
    }
}
