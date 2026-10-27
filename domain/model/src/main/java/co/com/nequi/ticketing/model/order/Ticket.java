package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.TicketStatus;

import java.util.Objects;

/**
 * One ticket of an order at a given instant. It is a view; it is not stored on its own.
 *
 * @param ticketId the ticket
 * @param status   the status derived from the effective status of the order
 */
public record Ticket(TicketId ticketId, TicketStatus status) {

    public Ticket {
        Objects.requireNonNull(ticketId, "Ticket id must not be null");
        Objects.requireNonNull(status, "Ticket status must not be null");
    }
}
