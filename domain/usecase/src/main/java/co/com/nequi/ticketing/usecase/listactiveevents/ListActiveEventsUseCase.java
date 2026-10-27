package co.com.nequi.ticketing.usecase.listactiveevents;

import co.com.nequi.ticketing.model.event.EventPage;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Lists the events on sale whose date is after the current instant.
 */
@RequiredArgsConstructor
public class ListActiveEventsUseCase {

    private final EventRepository eventRepository;
    private final ClockProvider clockProvider;

    public Mono<EventPage> list(EventPageQuery query) {
        return Mono.fromSupplier(clockProvider::now)
                .flatMap(now -> eventRepository.findListable(now, query));
    }
}
