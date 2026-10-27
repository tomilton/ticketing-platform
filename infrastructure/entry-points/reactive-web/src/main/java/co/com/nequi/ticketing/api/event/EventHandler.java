package co.com.nequi.ticketing.api.event;

import co.com.nequi.ticketing.api.config.EventPaginationProperties;
import co.com.nequi.ticketing.api.event.dto.request.CreateEventRequestDto;
import co.com.nequi.ticketing.api.event.mapper.EventMapManagement;
import co.com.nequi.ticketing.api.event.mapper.EventMapper;
import co.com.nequi.ticketing.api.helpers.QueryParameterParser;
import co.com.nequi.ticketing.api.helpers.RequestHelper;
import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.createevent.CreateEventUseCase;
import co.com.nequi.ticketing.usecase.getevent.GetEventUseCase;
import co.com.nequi.ticketing.usecase.listactiveevents.ListActiveEventsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Handlers of the event resource.
 */
@Component
@RequiredArgsConstructor
public class EventHandler {

    private final CreateEventUseCase createEventUseCase;
    private final GetEventUseCase getEventUseCase;
    private final ListActiveEventsUseCase listActiveEventsUseCase;
    private final EventMapper eventMapper;
    private final EventMapManagement mapManagement;
    private final BeanValidator beanValidator;
    private final EventPaginationProperties paginationProperties;

    public Mono<ServerResponse> createEvent(ServerRequest request) {
        return RequestHelper.bodyToMonoOrFailWhenEmpty(request, CreateEventRequestDto.class)
                .flatMap(beanValidator::validateBeanOrThrowError)
                .map(eventMapper::toCommand)
                .flatMap(createEventUseCase::create)
                .map(eventMapper::toResponse)
                .map(event -> mapManagement.buildResponse(event, SuccessMessage.CREATED))
                .flatMap(body -> ServerResponse.status(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body));
    }

    public Mono<ServerResponse> getEvent(ServerRequest request) {
        return Mono.fromCallable(() -> QueryParameterParser.parseEventId(request.pathVariable(QueryParameterParser.EVENT_ID)))
                .flatMap(getEventUseCase::get)
                .map(eventMapper::toResponse)
                .map(event -> mapManagement.buildResponse(event, SuccessMessage.SUCCESSFUL))
                .flatMap(EventHandler::ok);
    }

    public Mono<ServerResponse> listEvents(ServerRequest request) {
        return Mono.fromCallable(() -> new EventPageQuery(
                        QueryParameterParser.parseLimit(request.queryParam(QueryParameterParser.LIMIT).orElse(null),
                                paginationProperties.defaultSize(), paginationProperties.maxSize()),
                        request.queryParam(QueryParameterParser.CURSOR).orElse(null)))
                .flatMap(listActiveEventsUseCase::list)
                .onErrorMap(EventHandler::isInvalidCursor,
                        error -> new ValidationException(QueryParameterParser.CURSOR, "is not a valid cursor"))
                .map(eventMapper::toResponse)
                .map(page -> mapManagement.buildResponse(page, SuccessMessage.SUCCESSFUL))
                .flatMap(EventHandler::ok);
    }

    private static boolean isInvalidCursor(Throwable error) {
        return error instanceof TechnicalException technicalException
                && technicalException.getErrorMessage() == TechnicalErrorMessage.VALIDATION_ERROR;
    }

    private static <T> Mono<ServerResponse> ok(T body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
}
