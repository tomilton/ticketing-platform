package co.com.nequi.ticketing.api.commons;

import co.com.nequi.ticketing.model.success.SuccessMessage;

/**
 * Metadata of a successful response.
 *
 * @param code    the stable success code
 * @param message the success message
 */
public record Meta(String code, String message) {

    public static Meta getSuccessMeta(SuccessMessage successMessage) {
        return new Meta(successMessage.getCode(), successMessage.getMessage());
    }
}
