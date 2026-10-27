package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.OrderStatus;

import java.util.List;
import java.util.Objects;

/**
 * An order resolved at the instant of a query, so the entry point does not calculate statuses.
 *
 * @param order           the stored order
 * @param effectiveStatus the status at the instant of the query
 * @param tickets         the tickets with their status at the instant of the query
 */
public record OrderView(Order order, OrderStatus effectiveStatus, List<Ticket> tickets) {

    public OrderView {
        Objects.requireNonNull(order, "Order must not be null");
        Objects.requireNonNull(effectiveStatus, "Effective status must not be null");
        tickets = List.copyOf(tickets);
    }
}
