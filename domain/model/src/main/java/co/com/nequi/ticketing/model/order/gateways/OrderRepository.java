package co.com.nequi.ticketing.model.order.gateways;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderTransition;
import co.com.nequi.ticketing.model.order.ReservationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Persistence of orders. Every write that changes the inventory is atomic and conditional, so the
 * tickets are never oversold.
 */
public interface OrderRepository {

    /**
     * Reserves the tickets of a new order in a single atomic write: it checks that the event is on
     * sale, takes the tickets from one shard, raises the limit of the customer, stores the order with
     * its audit record and closes the idempotency key. It tries the shards in circular order from
     * {@code order.shardId}.
     *
     * @param order   the new order; its shard is the first one to try
     * @param event   the event of the order
     * @param context the limit of the customer and the idempotency key to close
     * @param now     the current instant
     * @return the stored order with the shard that served it; it fails with {@code EVENT_NOT_ACTIVE},
     * {@code TICKETS_SOLD_OUT}, {@code TICKET_LIMIT_EXCEEDED} or {@code REQUEST_IN_PROGRESS}
     */
    Mono<Order> reserve(Order order, Event event, ReservationContext context, Instant now);

    /**
     * Finds an order with a strongly consistent read.
     *
     * @param orderId the order
     * @return the order, or empty if it does not exist
     */
    Mono<Order> findById(OrderId orderId);

    /**
     * Writes a change of status only if the stored order still has the previous status and version,
     * together with the movement of the inventory and the audit record.
     *
     * @param transition the change of status
     * @param movement   how the change moves the inventory shard of the order
     * @return the order after the change; it fails with {@code INVALID_STATE_TRANSITION} if the
     * stored order changed
     */
    Mono<Order> apply(OrderTransition transition, InventoryMovement movement);

    /**
     * Marks an order as published, so the republication does not send it again. It does not change
     * the version of the order.
     *
     * @param orderId the order
     * @param at      when it was published
     * @return completion when the mark is stored
     */
    Mono<Void> markPublished(OrderId orderId, Instant at);

    /**
     * Finds the orders not yet published that were created before an instant.
     *
     * @param createdBefore the latest creation instant to include, exclusive
     * @return the identifiers of the orders, in no particular order
     */
    Flux<OrderId> findPendingPublication(Instant createdBefore);

    /**
     * Stores a complimentary order in a single atomic write: it checks that the event is on sale,
     * moves the tickets of one shard from available to complimentary and stores the order with its
     * audit record. It tries the shards in circular order from {@code order.shardId}. The limit of
     * the customer does not change.
     *
     * @param order the new complimentary order; its shard is the first one to try
     * @param event the event of the order
     * @param now   the current instant
     * @return the stored order with the shard that served it; it fails with {@code EVENT_NOT_ACTIVE}
     * or {@code TICKETS_SOLD_OUT}
     */
    Mono<Order> grantComplimentary(Order order, Event event, Instant now);

    /**
     * Finds the active orders whose reservation ended, without reading the orders in a final status.
     *
     * @param now   the current instant; an order expiring at this instant is included
     * @param limit the maximum number of orders to return
     * @return the identifiers of the expired orders, in no particular order
     */
    Flux<OrderId> findExpired(Instant now, Integer limit);
}
