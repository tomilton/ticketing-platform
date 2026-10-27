package co.com.nequi.ticketing.model.order.event;

import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.EventId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Notice that an order was accepted and waits to be processed. It only carries identifiers.
 *
 * @param messageId  the identifier of this message
 * @param orderId    the accepted order
 * @param eventId    the event of the order
 * @param quantity   the tickets of the order
 * @param occurredAt when the order was accepted
 */
public record OrderPlacedMessage(UUID messageId, OrderId orderId, EventId eventId, Quantity quantity,
                                 Instant occurredAt) {

    public OrderPlacedMessage {
        Objects.requireNonNull(messageId, "Message id must not be null");
        Objects.requireNonNull(orderId, "Order id must not be null");
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(quantity, "Quantity must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence instant must not be null");
    }
}
