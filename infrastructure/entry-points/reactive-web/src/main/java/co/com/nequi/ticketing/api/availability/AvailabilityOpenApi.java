package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.api.availability.dto.response.AvailabilityResponseDto;
import co.com.nequi.ticketing.api.commons.ErrorEnvelope;
import co.com.nequi.ticketing.api.commons.Meta;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.UtilityClass;
import org.springdoc.core.fn.builders.operation.Builder;

import java.util.List;
import java.util.UUID;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

/**
 * OpenAPI documentation of the availability routes, equivalent to the feature contract.
 */
@UtilityClass
public class AvailabilityOpenApi {

    private static final String TAG = "Availability";

    public static void getAvailability(Builder operation) {
        operation.operationId("getAvailability")
                .tag(TAG)
                .summary("Current ticket counters of an event (strongly consistent read)")
                .parameter(eventIdParameter())
                .response(responseBuilder().responseCode("200")
                        .description("Counters (meta.code TKS0001); available + reserved + sold + complimentary = capacity")
                        .implementation(AvailabilityEnvelope.class))
                .response(error("400", "TKT0001 eventId is not a UUID"))
                .response(error("404", "TKB0001 event not found"))
                .response(error("500", "TKT0005, TKT0009"));
    }

    public static void getBatchAvailability(Builder operation) {
        operation.operationId("getBatchAvailability")
                .tag(TAG)
                .summary("Availability of up to 100 events; unknown ids are omitted")
                .parameter(parameterBuilder().name("ids").in(ParameterIn.QUERY).required(true)
                        .description("Comma-separated event UUIDs, 1 to 100 values").implementation(String.class))
                .response(responseBuilder().responseCode("200")
                        .description("One element per existing event (meta.code TKS0001); empty list if none exist")
                        .implementation(AvailabilityListEnvelope.class))
                .response(error("400", "TKT0001 missing or empty ids, more than 100 ids, or an id that is not a UUID"))
                .response(error("500", "TKT0005, TKT0009"));
    }

    static org.springdoc.core.fn.builders.parameter.Builder eventIdParameter() {
        return parameterBuilder().name("eventId").in(ParameterIn.PATH).required(true).implementation(UUID.class);
    }

    static org.springdoc.core.fn.builders.apiresponse.Builder error(String code, String description) {
        return responseBuilder().responseCode(code).description(description).implementation(ErrorEnvelope.class);
    }

    @Schema(name = "AvailabilityEnvelope")
    record AvailabilityEnvelope(Meta meta, AvailabilityResponseDto data) {
    }

    @Schema(name = "AvailabilityListEnvelope")
    record AvailabilityListEnvelope(Meta meta, List<AvailabilityResponseDto> data) {
    }
}
