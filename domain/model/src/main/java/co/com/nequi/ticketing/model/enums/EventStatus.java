package co.com.nequi.ticketing.model.enums;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Lifecycle status of an event. Only an active event is on sale.
 */
public enum EventStatus {
    ACTIVE,
    CANCELLED,
    FINISHED;

    private static final String VALUE_SEPARATOR = ", ";

    /**
     * Tells whether tickets of an event in this status can be sold.
     *
     * @return {@code true} only for {@link #ACTIVE}
     */
    public boolean isOnSale() {
        return this == ACTIVE;
    }

    /**
     * Tells whether this status is terminal.
     *
     * @return {@code true} for {@link #CANCELLED} and {@link #FINISHED}
     */
    public boolean isFinal() {
        return this != ACTIVE;
    }

    /**
     * Tells whether an event can move from this status to the target status.
     *
     * @param target the status to move to
     * @return {@code true} only from {@link #ACTIVE} to a final status
     */
    public boolean canTransitionTo(EventStatus target) {
        return isOnSale() && target.isFinal();
    }

    /**
     * Lists the names of all statuses, for validation messages.
     *
     * @return the status names separated by commas
     */
    public static String validValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(VALUE_SEPARATOR));
    }
}
