package co.com.nequi.ticketing.api.event;

import co.com.nequi.ticketing.api.commons.ErrorEnvelope;
import co.com.nequi.ticketing.api.commons.Meta;
import co.com.nequi.ticketing.api.event.dto.request.CreateEventRequestDto;
import co.com.nequi.ticketing.api.event.dto.response.EventResponseDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.UtilityClass;
import org.springdoc.core.fn.builders.operation.Builder;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

/**
 * OpenAPI documentation of the event routes, equivalent to the feature contract.
 */
@UtilityClass
public class EventOpenApi {

    private static final String TAG = "Events";

    public static void createEvent(Builder operation) {
        operation.operationId("createEvent")
                .tag(TAG)
                .summary("Create an event and its inventory")
                .requestBody(requestBodyBuilder().required(true).implementation(CreateEventRequestDto.class))
                .response(responseBuilder().responseCode("201").description("Event created (meta.code TKS0002)")
                        .implementation(EventEnvelope.class))
                .response(error("400", "TKT0001 validation error, TKT0002 invalid JSON, TKT0003 empty body"))
                .response(error("422", "TKB0010 event date is not in the future"))
                .response(error("500", "TKT0005 database error, TKT0009 unexpected error"));
    }

    private static org.springdoc.core.fn.builders.apiresponse.Builder error(String code, String description) {
        return responseBuilder().responseCode(code).description(description).implementation(ErrorEnvelope.class);
    }

    @Schema(name = "EventEnvelope")
    record EventEnvelope(Meta meta, EventResponseDto data) {
    }
}
