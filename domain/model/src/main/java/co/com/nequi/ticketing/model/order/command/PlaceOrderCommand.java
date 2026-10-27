package co.com.nequi.ticketing.model.order.command;

import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;

import java.util.Objects;

/**
 * Request of a customer to reserve tickets of an event.
 *
 * @param customerId the customer
 * @param key        the idempotency key of the request
 * @param eventId    the event
 * @param quantity   the number of tickets
 */
public record PlaceOrderCommand(CustomerId customerId, IdempotencyKey key, EventId eventId, Quantity quantity) {

    public PlaceOrderCommand {
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(key, "Idempotency key must not be null");
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(quantity, "Quantity must not be null");
    }
}
