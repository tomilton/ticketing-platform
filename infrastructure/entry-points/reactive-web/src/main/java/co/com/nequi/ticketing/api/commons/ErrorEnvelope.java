package co.com.nequi.ticketing.api.commons;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Shape of every error response, used by the API documentation.
 *
 * @param errors the errors, at least one
 */
@Schema(name = "ErrorEnvelope")
public record ErrorEnvelope(List<ErrorResponse> errors) {
}
