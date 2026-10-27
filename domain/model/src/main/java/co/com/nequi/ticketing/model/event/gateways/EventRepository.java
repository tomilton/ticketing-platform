package co.com.nequi.ticketing.model.event.gateways;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.EventPage;
import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.EventId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Persistence of events.
 */
public interface EventRepository {

    /**
     * Stores a new event together with its inventory shards in a single atomic write.
     *
     * @param event  the new event
     * @param shards the initial shards of the event
     * @return the stored event
     */
    Mono<Event> create(Event event, List<InventoryShard> shards);

    /**
     * Finds an event in any status.
     *
     * @param eventId the event
     * @return the event, or empty if it does not exist
     */
    Mono<Event> findById(EventId eventId);

    /**
     * Finds one page of the events on sale with a date after {@code now}, ordered by date ascending.
     *
     * @param now   the current instant
     * @param query the page size and position
     * @return the page, with no items when there are none
     */
    Mono<EventPage> findListable(Instant now, EventPageQuery query);

    /**
     * Finds several events at once. Events that do not exist are left out.
     *
     * @param eventIds the events
     * @return the existing events, in no particular order
     */
    Flux<Event> findAllById(Set<EventId> eventIds);
}
