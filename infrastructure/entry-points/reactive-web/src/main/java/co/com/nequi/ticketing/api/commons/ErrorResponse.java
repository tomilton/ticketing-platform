package co.com.nequi.ticketing.api.commons;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One error of the {@code errors} list.
 *
 * @param reason  the message of the catalog entry
 * @param code    the stable catalog code
 * @param message the detail, naming the affected field or parameter when there is one
 * @param domain  the path of the request
 */
@Schema(name = "ErrorResponse")
public record ErrorResponse(String reason, String code, String message, String domain) {

    public ErrorResponse withDomain(String path) {
        return new ErrorResponse(reason, code, message, path);
    }
}
