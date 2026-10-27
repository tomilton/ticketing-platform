package co.com.nequi.ticketing.api.event;

import co.com.nequi.ticketing.api.commons.ErrorEnvelope;
import co.com.nequi.ticketing.api.commons.Meta;
import co.com.nequi.ticketing.api.event.dto.request.CreateEventRequestDto;
import co.com.nequi.ticketing.api.event.dto.response.EventResponseDto;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.experimental.UtilityClass;
import org.springdoc.core.fn.builders.operation.Builder;

import java.util.UUID;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
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

    public static void getEvent(Builder operation) {
        operation.operationId("getEvent")
                .tag(TAG)
                .summary("Get an event by id, in any status")
                .parameter(eventIdParameter())
                .response(responseBuilder().responseCode("200").description("Event (meta.code TKS0001)")
                        .implementation(EventEnvelope.class))
                .response(error("400", "TKT0001 eventId is not a UUID"))
                .response(error("404", "TKB0001 event not found"))
                .response(error("500", "TKT0005, TKT0009"));
    }

    private static org.springdoc.core.fn.builders.parameter.Builder eventIdParameter() {
        return parameterBuilder().name("eventId").in(ParameterIn.PATH).required(true).implementation(UUID.class);
    }

    private static org.springdoc.core.fn.builders.apiresponse.Builder error(String code, String description) {
        return responseBuilder().responseCode(code).description(description).implementation(ErrorEnvelope.class);
    }

    @Schema(name = "EventEnvelope")
    record EventEnvelope(Meta meta, EventResponseDto data) {
    }
}
