package co.com.nequi.ticketing.api.handle.exceptions;

import co.com.nequi.ticketing.api.commons.ErrorResponse;
import org.springframework.http.HttpStatus;

/**
 * Error body and HTTP status of a failed request.
 *
 * @param response the error of the {@code errors} list
 * @param status   the HTTP status
 */
public record ErrorResult(ErrorResponse response, HttpStatus status) {
}
