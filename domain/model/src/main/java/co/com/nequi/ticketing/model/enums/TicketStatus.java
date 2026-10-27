package co.com.nequi.ticketing.model.enums;

/**
 * Status of one ticket. It is not stored: it is derived from the effective status of its order.
 */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    /**
     * Derives the status of the tickets of an order.
     *
     * @param effective the effective status of the order
     * @return the status of each ticket of the order; a cancelled or expired order frees its tickets
     */
    public static TicketStatus of(OrderStatus effective) {
        return switch (effective) {
            case RESERVED -> RESERVED;
            case PENDING_CONFIRMATION -> PENDING_CONFIRMATION;
            case SOLD -> SOLD;
            case CANCELLED, EXPIRED -> AVAILABLE;
        };
    }
}
