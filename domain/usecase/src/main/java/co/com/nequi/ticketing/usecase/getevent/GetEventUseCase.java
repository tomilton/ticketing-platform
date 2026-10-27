package co.com.nequi.ticketing.usecase.getevent;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;

/**
 * Gets an event in any status; a missing event is a business error.
 */
@RequiredArgsConstructor
public class GetEventUseCase {

    private final EventRepository eventRepository;

    public Mono<Event> get(EventId eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_FOUND.build())));
    }
}
