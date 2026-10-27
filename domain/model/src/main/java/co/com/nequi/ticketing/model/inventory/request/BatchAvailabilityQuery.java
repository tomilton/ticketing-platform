package co.com.nequi.ticketing.model.inventory.request;

import co.com.nequi.ticketing.model.shared.EventId;

import java.util.List;

/**
 * Request for the availability of several events at once.
 *
 * @param eventIds the events in the order of the request; it may contain repeated ids
 */
public record BatchAvailabilityQuery(List<EventId> eventIds) {

    public BatchAvailabilityQuery {
        eventIds = List.copyOf(eventIds);
        if (eventIds.isEmpty()) {
            throw new IllegalArgumentException("Event ids must not be empty");
        }
    }
}
