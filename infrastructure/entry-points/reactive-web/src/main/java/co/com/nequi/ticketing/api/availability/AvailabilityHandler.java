package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapManagement;
import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapper;
import co.com.nequi.ticketing.api.config.AvailabilityBatchProperties;
import co.com.nequi.ticketing.api.helpers.QueryParameterParser;
import co.com.nequi.ticketing.model.inventory.request.BatchAvailabilityQuery;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getbatchavailability.GetBatchAvailabilityUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handlers of the availability resource.
 */
@Component
@RequiredArgsConstructor
public class AvailabilityHandler {

    private final GetAvailabilityUseCase getAvailabilityUseCase;
    private final GetBatchAvailabilityUseCase getBatchAvailabilityUseCase;
    private final AvailabilityMapper availabilityMapper;
    private final AvailabilityMapManagement mapManagement;
    private final AvailabilityBatchProperties batchProperties;

    public Mono<ServerResponse> getAvailability(ServerRequest request) {
        return Mono.fromCallable(() -> QueryParameterParser.parseEventId(request.pathVariable(QueryParameterParser.EVENT_ID)))
                .flatMap(getAvailabilityUseCase::get)
                .map(availabilityMapper::toResponse)
                .map(availability -> mapManagement.buildResponse(availability, SuccessMessage.SUCCESSFUL))
                .flatMap(AvailabilityHandler::ok);
    }

    public Mono<ServerResponse> getBatchAvailability(ServerRequest request) {
        return Mono.fromCallable(() -> new BatchAvailabilityQuery(QueryParameterParser.parseEventIds(
                        request.queryParam(QueryParameterParser.IDS).orElse(null), batchProperties.batchMaxIds())))
                .flatMapMany(getBatchAvailabilityUseCase::get)
                .map(availabilityMapper::toResponse)
                .collectList()
                .map(availabilities -> mapManagement.buildResponse(availabilities, SuccessMessage.SUCCESSFUL))
                .flatMap(AvailabilityHandler::ok);
    }

    private static <T> Mono<ServerResponse> ok(T body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
