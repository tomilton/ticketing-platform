package co.com.nequi.ticketing.model.order;

import java.util.Objects;

/**
 * A valid change of status of an order. The adapter writes it only if the stored order still has
 * the status and version of {@code previous}, together with its audit record.
 *
 * @param previous the order as it was read
 * @param next     the order after the change, with the next version
 * @param change   the audit record of the change
 */
public record OrderTransition(Order previous, Order next, OrderStatusChange change) {

    public OrderTransition {
        Objects.requireNonNull(previous, "Previous order must not be null");
        Objects.requireNonNull(next, "Next order must not be null");
        Objects.requireNonNull(change, "Change must not be null");
    }
}
