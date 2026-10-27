package co.com.nequi.ticketing.model.event.request;

import java.util.Objects;

/**
 * Request for one page of the events on sale.
 *
 * @param limit  the maximum number of events of the page
 * @param cursor the opaque position returned by the previous page, or {@code null} for the first one
 */
public record EventPageQuery(Integer limit, String cursor) {

    public EventPageQuery {
        Objects.requireNonNull(limit, "Limit must not be null");
    }
}
