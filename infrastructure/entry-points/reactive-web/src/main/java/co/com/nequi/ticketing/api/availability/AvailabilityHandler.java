package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapManagement;
import co.com.nequi.ticketing.api.availability.dto.response.AvailabilityResponseDto;
import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapper;
import co.com.nequi.ticketing.api.config.AvailabilityBatchProperties;
import co.com.nequi.ticketing.api.config.AvailabilityStreamProperties;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResult;
import co.com.nequi.ticketing.api.helpers.QueryParameterParser;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.request.BatchAvailabilityQuery;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getbatchavailability.GetBatchAvailabilityUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Handlers of the availability resource.
 */
@Component
@RequiredArgsConstructor
public class AvailabilityHandler {

    private static final String AVAILABILITY_EVENT = "availability";
    private static final String RECONNECT_EVENT = "reconnect";
    private static final String ERROR_EVENT = "error";
    private static final String KEEPALIVE_COMMENT = "keepalive";
    private static final ParameterizedTypeReference<ServerSentEvent<?>> EVENT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final GetAvailabilityUseCase getAvailabilityUseCase;
    private final GetBatchAvailabilityUseCase getBatchAvailabilityUseCase;
    private final AvailabilityMapper availabilityMapper;
    private final AvailabilityMapManagement mapManagement;
    private final AvailabilityBatchProperties batchProperties;
    private final AvailabilityStreamProperties streamProperties;
    private final AvailabilityStreamHub streamHub;
    private final ErrorResponseBuilder errorResponseBuilder;

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

    public Mono<ServerResponse> streamAvailability(ServerRequest request) {
        return Mono.fromCallable(() -> QueryParameterParser.parseEventId(request.pathVariable(QueryParameterParser.EVENT_ID)))
                .flatMap(eventId -> getAvailabilityUseCase.get(eventId).thenReturn(eventId))
                .flatMap(eventId -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(buildStream(eventId, request.path()), EVENT_TYPE));
    }

    private Flux<ServerSentEvent<?>> buildStream(EventId eventId, String path) {
        Flux<ServerSentEvent<?>> availability = streamHub.watch(eventId)
                .map(availabilityMapper::toResponse)
                .index((index, response) -> availabilityEvent(response, index == 0));
        Flux<ServerSentEvent<?>> heartbeat = Flux.interval(streamProperties.heartbeat(), streamProperties.heartbeat())
                .map(tick -> ServerSentEvent.builder().comment(KEEPALIVE_COMMENT).build());
        return Flux.merge(availability, heartbeat)
                .take(streamProperties.maxDuration())
                .concatWith(Mono.fromSupplier(this::reconnectEvent))
                .onErrorResume(error -> toErrorResult(error).map(result -> errorEvent(result, path)));
    }

    private ServerSentEvent<?> availabilityEvent(AvailabilityResponseDto response, boolean isFirst) {
        ServerSentEvent.Builder<AvailabilityResponseDto> event = ServerSentEvent.builder(response).event(AVAILABILITY_EVENT);
        return isFirst ? event.retry(streamProperties.retry()).build() : event.build();
    }

    private ServerSentEvent<?> reconnectEvent() {
        return ServerSentEvent.builder(Map.of()).event(RECONNECT_EVENT).retry(streamProperties.retry()).build();
    }

    private static ServerSentEvent<?> errorEvent(ErrorResult result, String path) {
        return ServerSentEvent.builder(result.response().withDomain(path)).event(ERROR_EVENT).build();
    }

    private Mono<ErrorResult> toErrorResult(Throwable error) {
        return switch (error) {
            case BusinessException businessException -> errorResponseBuilder.buildErrorResponse(businessException);
            case TechnicalException technicalException -> errorResponseBuilder.buildErrorResponse(technicalException);
            default -> errorResponseBuilder.buildErrorResponse(error);
        };
    }

    private static <T> Mono<ServerResponse> ok(T body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
