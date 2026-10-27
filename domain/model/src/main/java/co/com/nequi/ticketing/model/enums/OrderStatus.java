package co.com.nequi.ticketing.model.enums;

import java.util.Map;
import java.util.Set;

/**
 * Lifecycle status of an order. An active order holds its tickets until it is sold, cancelled or
 * expired.
 */
public enum OrderStatus {
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    CANCELLED,
    EXPIRED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            RESERVED, Set.of(PENDING_CONFIRMATION, EXPIRED, CANCELLED),
            PENDING_CONFIRMATION, Set.of(SOLD, CANCELLED, EXPIRED));

    /**
     * Tells whether the order still waits for its outcome.
     *
     * @return {@code true} for {@link #RESERVED} and {@link #PENDING_CONFIRMATION}
     */
    public boolean isActive() {
        return this == RESERVED || this == PENDING_CONFIRMATION;
    }

    /**
     * Tells whether this status is terminal.
     *
     * @return {@code true} for {@link #SOLD}, {@link #CANCELLED} and {@link #EXPIRED}
     */
    public boolean isFinal() {
        return !isActive();
    }

    /**
     * Tells whether the tickets of an order in this status count for the limit of the customer.
     *
     * @return {@code true} for {@link #RESERVED}, {@link #PENDING_CONFIRMATION} and {@link #SOLD}
     */
    public boolean holdsTickets() {
        return isActive() || this == SOLD;
    }

    /**
     * Tells whether an order can move from this status to the target status.
     *
     * @param target the status to move to
     * @return {@code true} only for the transitions of the state machine
     */
    public boolean canTransitionTo(OrderStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
