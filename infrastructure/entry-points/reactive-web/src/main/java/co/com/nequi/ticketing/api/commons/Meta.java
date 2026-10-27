package co.com.nequi.ticketing.api.commons;

import co.com.nequi.ticketing.model.success.SuccessMessage;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Metadata of a successful response.
 *
 * @param code    the stable success code
 * @param message the success message
 */
@Schema(name = "Meta")
public record Meta(String code, String message) {

    public static Meta getSuccessMeta(SuccessMessage successMessage) {
        return new Meta(successMessage.getCode(), successMessage.getMessage());
    }
}
