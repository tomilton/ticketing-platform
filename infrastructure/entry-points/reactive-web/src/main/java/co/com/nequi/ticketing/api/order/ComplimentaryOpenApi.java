package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.commons.ErrorEnvelope;
import co.com.nequi.ticketing.api.order.dto.request.GrantComplimentaryRequestDto;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.experimental.UtilityClass;
import org.springdoc.core.fn.builders.operation.Builder;

import java.util.UUID;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

/**
 * OpenAPI documentation of the complimentary route, equivalent to the feature contract.
 */
@UtilityClass
public class ComplimentaryOpenApi {

    private static final String TAG = "Complimentary";

    public static void grantComplimentary(Builder operation) {
        operation.operationId("grantComplimentary")
                .tag(TAG)
                .summary("Grant 1 to 50 complimentary tickets of an active event to a customer")
                .description("Creates a final COMPLIMENTARY order and moves the quantity from available to "
                        + "complimentary in the same transaction. Complimentary tickets do not count toward the "
                        + "per-customer limit. Requires the admin role (enforced in feature 004). Not idempotent in "
                        + "this version: repeating the request grants again")
                .parameter(parameterBuilder().name("eventId").in(ParameterIn.PATH).required(true)
                        .implementation(UUID.class))
                .requestBody(requestBodyBuilder().required(true).implementation(GrantComplimentaryRequestDto.class))
                .response(responseBuilder().responseCode("201")
                        .description("Complimentary order created (meta.code TKS0002); data is an OrderResponse "
                                + "with status COMPLIMENTARY")
                        .implementation(OrderOpenApi.OrderEnvelope.class))
                .response(error("400", "TKT0001 validation error (customerId, quantity, eventId), TKT0002 invalid "
                        + "JSON, TKT0003 empty body"))
                .response(error("404", "TKB0001 event not found"))
                .response(error("409", "TKB0003 not enough available tickets, TKB0007 event not active or already "
                        + "started"))
                .response(error("500", "TKT0005, TKT0009"));
    }

    private static org.springdoc.core.fn.builders.apiresponse.Builder error(String code, String description) {
        return responseBuilder().responseCode(code).description(description).implementation(ErrorEnvelope.class);
    }
}
