package co.com.nequi.ticketing.api.event;

import co.com.nequi.ticketing.api.event.dto.request.CreateEventRequestDto;
import co.com.nequi.ticketing.api.event.mapper.EventMapManagement;
import co.com.nequi.ticketing.api.event.mapper.EventMapper;
import co.com.nequi.ticketing.api.helpers.RequestHelper;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import co.com.nequi.ticketing.usecase.createevent.CreateEventUseCase;
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
    private final EventMapper eventMapper;
    private final EventMapManagement mapManagement;
    private final BeanValidator beanValidator;

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
}
