package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.ChangeActor;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.enums.TicketStatus;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.INVALID_STATE_TRANSITION;

/**
 * Order aggregate: the tickets of one event reserved by a customer until they are paid, cancelled
 * or expired. It is immutable; every change returns a new instance with the next version.
 */
@Getter
public class Order {

    private static final Long INITIAL_VERSION = 1L;

    private final OrderId orderId;
    private final CustomerId customerId;
    private final EventId eventId;
    private final Quantity quantity;
    private final List<TicketId> tickets;
    private final OrderStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Integer shardId;
    private final Long version;

    @Builder(toBuilder = true)
    private Order(OrderId orderId, CustomerId customerId, EventId eventId, Quantity quantity, List<TicketId> tickets,
                  OrderStatus status, Instant createdAt, Instant expiresAt, Integer shardId, Long version) {
        this.orderId = Objects.requireNonNull(orderId, "Order id must not be null");
        this.customerId = Objects.requireNonNull(customerId, "Customer id must not be null");
        this.eventId = Objects.requireNonNull(eventId, "Event id must not be null");
        this.quantity = Objects.requireNonNull(quantity, "Quantity must not be null");
        this.tickets = List.copyOf(Objects.requireNonNull(tickets, "Tickets must not be null"));
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Creation instant must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "Expiration instant must not be null");
        this.shardId = Objects.requireNonNull(shardId, "Shard id must not be null");
        this.version = Objects.requireNonNull(version, "Version must not be null");
        if (this.tickets.size() != quantity.value()) {
            throw new IllegalArgumentException("Tickets must match the quantity");
        }
    }

    /**
     * Creates a new reserved order with one new ticket per unit of the quantity.
     *
     * @param orderId    the identifier of the order
     * @param customerId the owner of the order
     * @param eventId    the event of the tickets
     * @param quantity   the number of tickets
     * @param shardId    the first inventory shard to try
     * @param now        the current instant; it is truncated to milliseconds
     * @param ttl        how long the reservation is valid
     * @return the order in {@link OrderStatus#RESERVED} with version 1
     */
    public static Order place(OrderId orderId, CustomerId customerId, EventId eventId, Quantity quantity,
                              Integer shardId, Instant now, Duration ttl) {
        Instant createdAt = now.truncatedTo(ChronoUnit.MILLIS);
        return Order.builder()
                .orderId(orderId)
                .customerId(customerId)
                .eventId(eventId)
                .quantity(quantity)
                .tickets(Stream.generate(TicketId::newId).limit(quantity.value()).toList())
                .status(OrderStatus.RESERVED)
                .createdAt(createdAt)
                .expiresAt(createdAt.plus(ttl))
                .shardId(shardId)
                .version(INITIAL_VERSION)
                .build();
    }

    /**
     * Audit record of the creation of the order.
     *
     * @return the change to {@link OrderStatus#RESERVED} caused by the customer
     */
    public OrderStatusChange placementChange() {
        return new OrderStatusChange(orderId, null, OrderStatus.RESERVED, StatusChangeReason.PLACED,
                ChangeActor.CUSTOMER, createdAt);
    }

    /**
     * Status of the order at an instant. An active order whose reservation ended is expired even if
     * the stored status still says otherwise.
     *
     * @param now the instant of the query
     * @return {@link OrderStatus#EXPIRED} when the order is active and {@code now} is not before
     * {@code expiresAt}; the stored status otherwise
     */
    public OrderStatus effectiveStatus(Instant now) {
        return status.isActive() && !now.isBefore(expiresAt) ? OrderStatus.EXPIRED : status;
    }

    /**
     * Tells whether the reservation still holds the tickets.
     *
     * @param now the current instant
     * @return {@code true} if the order is active and {@code now} is before {@code expiresAt}
     */
    public boolean isReservationValid(Instant now) {
        return status.isActive() && now.isBefore(expiresAt);
    }

    /**
     * Tells whether the order belongs to a customer.
     *
     * @param candidate the customer to compare
     * @return {@code true} if the candidate is the owner
     */
    public boolean isOwnedBy(CustomerId candidate) {
        return customerId.equals(candidate);
    }

    /**
     * Tickets of the order with their status at an instant.
     *
     * @param now the instant of the query
     * @return one ticket per identifier, with the status derived from the effective status
     */
    public List<Ticket> ticketsAt(Instant now) {
        TicketStatus ticketStatus = TicketStatus.of(effectiveStatus(now));
        return tickets.stream().map(ticketId -> new Ticket(ticketId, ticketStatus)).toList();
    }

    /**
     * Moves the order to another status, as the worker does when it processes the order.
     *
     * @param target the new status
     * @param reason why the status changes
     * @param at     when the status changes
     * @return the transition with this order, the next one and its audit record
     * @throws co.com.nequi.ticketing.model.exception.BusinessException with {@code INVALID_STATE_TRANSITION}
     *                                                                  if the state machine does not allow it
     */
    public OrderTransition transitionTo(OrderStatus target, StatusChangeReason reason, Instant at) {
        if (!status.canTransitionTo(target)) {
            throw INVALID_STATE_TRANSITION.build();
        }
        Order next = toBuilder().status(target).version(version + 1).build();
        return new OrderTransition(this, next,
                new OrderStatusChange(orderId, status, target, reason, ChangeActor.WORKER, at));
    }
}
