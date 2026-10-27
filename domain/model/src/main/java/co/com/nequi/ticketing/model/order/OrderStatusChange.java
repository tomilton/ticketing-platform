package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.ChangeActor;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;

import java.time.Instant;
import java.util.Objects;

/**
 * Audit record of one change of status of an order. It is written once and never updated.
 *
 * @param orderId the order
 * @param from    the previous status, {@code null} only when the order is created
 * @param to      the new status
 * @param reason  why the status changed
 * @param actor   who caused the change
 * @param at      when the status changed
 */
public record OrderStatusChange(OrderId orderId, OrderStatus from, OrderStatus to, StatusChangeReason reason,
                                ChangeActor actor, Instant at) {

    public OrderStatusChange {
        Objects.requireNonNull(orderId, "Order id must not be null");
        Objects.requireNonNull(to, "Target status must not be null");
        Objects.requireNonNull(reason, "Reason must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(at, "Instant must not be null");
    }
}
