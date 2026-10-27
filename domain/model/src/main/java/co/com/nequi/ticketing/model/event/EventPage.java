package co.com.nequi.ticketing.model.event;

import java.util.List;

/**
 * One page of the events on sale, ordered by date.
 *
 * @param items      the events of the page
 * @param nextCursor the opaque position of the next page, or {@code null} when there are no more
 */
public record EventPage(List<Event> items, String nextCursor) {

    public EventPage {
        items = List.copyOf(items);
    }
}
