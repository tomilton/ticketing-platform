package co.com.nequi.ticketing.model.order.command;

import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;

import java.util.Objects;

/**
 * Request of an administrator to give complimentary tickets of an event to a customer.
 *
 * @param eventId    the event
 * @param customerId the customer who receives the tickets
 * @param quantity   the number of tickets
 */
public record GrantComplimentaryCommand(EventId eventId, CustomerId customerId, ComplimentaryQuantity quantity) {

    public GrantComplimentaryCommand {
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(quantity, "Quantity must not be null");
    }
}
