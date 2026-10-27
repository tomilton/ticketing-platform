package co.com.nequi.ticketing.model.order;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier of one ticket of an order, generated when the order is placed.
 *
 * @param value the UUID that identifies the ticket
 */
public record TicketId(UUID value) {

    public TicketId {
        Objects.requireNonNull(value, "Ticket id value must not be null");
    }

    /**
     * Generates a new random identifier.
     *
     * @return a new identifier
     */
    public static TicketId newId() {
        return new TicketId(UUID.randomUUID());
    }
}
