package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.ChangeActor;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.enums.TicketStatus;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00.123456Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-27T10:00:00.123Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final EventId EVENT_ID = EventId.newId();
    private static final Integer SHARD_ID = 2;

    @Test
    void shouldCreateReservedOrderWhenOrderIsPlaced() {
        // Given
        OrderId orderId = OrderId.newId();

        // When
        Order order = Order.place(orderId, CUSTOMER_ID, EVENT_ID, new Quantity(3), SHARD_ID, NOW, TTL);

        // Then
        assertThat(order.getOrderId()).isEqualTo(orderId);
        assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(order.getEventId()).isEqualTo(EVENT_ID);
        assertThat(order.getQuantity()).isEqualTo(new Quantity(3));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
        assertThat(order.getVersion()).isEqualTo(1L);
        assertThat(order.getShardId()).isEqualTo(SHARD_ID);
        assertThat(order.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(order.getExpiresAt()).isEqualTo(CREATED_AT.plus(TTL));
        assertThat(order.getTickets()).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void shouldRecordCustomerChangeWhenOrderIsPlaced() {
        // Given
        Order order = reserved();

        // When / Then
        assertThat(order.placementChange()).isEqualTo(new OrderStatusChange(order.getOrderId(), null,
                OrderStatus.RESERVED, StatusChangeReason.PLACED, ChangeActor.CUSTOMER, CREATED_AT));
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenTicketsDoNotMatchQuantity() {
        // Given
        Order.OrderBuilder builder = reserved().toBuilder().tickets(List.of(TicketId.newId()));

        // When / Then
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenFieldIsNull() {
        // Given
        Order order = reserved();

        List<Order.OrderBuilder> builders = List.of(order.toBuilder().orderId(null),
                order.toBuilder().customerId(null), order.toBuilder().eventId(null), order.toBuilder().quantity(null),
                order.toBuilder().tickets(null), order.toBuilder().status(null), order.toBuilder().createdAt(null),
                order.toBuilder().expiresAt(null), order.toBuilder().shardId(null), order.toBuilder().version(null));

        // When / Then
        builders.forEach(builder -> assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class));
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"RESERVED", "PENDING_CONFIRMATION"})
    void shouldKeepStatusWhenActiveOrderIsBeforeExpiration(OrderStatus status) {
        // Given
        Order order = reserved().toBuilder().status(status).build();
        Instant justBefore = order.getExpiresAt().minusMillis(1);

        // When / Then
        assertThat(order.effectiveStatus(justBefore)).isEqualTo(status);
        assertThat(order.isReservationValid(justBefore)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L})
    void shouldReturnExpiredWhenActiveOrderReachesExpiration(Long millisAfterExpiration) {
        // Given
        Order order = reserved();
        Instant instant = order.getExpiresAt().plusMillis(millisAfterExpiration);

        // When / Then
        assertThat(order.effectiveStatus(instant)).isEqualTo(OrderStatus.EXPIRED);
        assertThat(order.isReservationValid(instant)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SOLD", "CANCELLED", "EXPIRED"})
    void shouldKeepFinalStatusWhenOrderIsFinal(OrderStatus status) {
        // Given
        Order order = reserved().toBuilder().status(status).build();

        // When / Then
        assertThat(order.effectiveStatus(order.getExpiresAt().plusSeconds(1))).isEqualTo(status);
        assertThat(order.effectiveStatus(NOW)).isEqualTo(status);
        assertThat(order.isReservationValid(NOW)).isFalse();
    }

    @Test
    void shouldTellOwnerWhenCustomerIsCompared() {
        // Given
        Order order = reserved();

        // When / Then
        assertThat(order.isOwnedBy(CUSTOMER_ID)).isTrue();
        assertThat(order.isOwnedBy(new CustomerId(UUID.randomUUID()))).isFalse();
    }

    @Test
    void shouldDeriveTicketStatusWhenTicketsAreQueried() {
        // Given
        Order order = reserved();

        // When
        List<Ticket> beforeExpiration = order.ticketsAt(NOW);
        List<Ticket> afterExpiration = order.ticketsAt(order.getExpiresAt());

        // Then
        assertThat(beforeExpiration).extracting(Ticket::ticketId).containsExactlyElementsOf(order.getTickets());
        assertThat(beforeExpiration).extracting(Ticket::status).containsOnly(TicketStatus.RESERVED);
        assertThat(afterExpiration).extracting(Ticket::status).containsOnly(TicketStatus.AVAILABLE);
    }

    @Test
    void shouldReturnTransitionWhenTargetIsAllowed() {
        // Given
        Order order = reserved();
        Instant at = NOW.plusSeconds(1);

        // When
        OrderTransition transition = order.transitionTo(OrderStatus.PENDING_CONFIRMATION,
                StatusChangeReason.PAYMENT_REQUESTED, at);

        // Then
        assertThat(transition.previous()).isSameAs(order);
        assertThat(transition.next()).usingRecursiveComparison()
                .isEqualTo(order.toBuilder().status(OrderStatus.PENDING_CONFIRMATION).version(2L).build());
        assertThat(transition.change()).isEqualTo(new OrderStatusChange(order.getOrderId(), OrderStatus.RESERVED,
                OrderStatus.PENDING_CONFIRMATION, StatusChangeReason.PAYMENT_REQUESTED, ChangeActor.WORKER, at));
    }

    @Test
    void shouldThrowInvalidStateTransitionWhenTargetIsNotAllowed() {
        // Given
        Order order = reserved();

        // When / Then
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SOLD, StatusChangeReason.PAYMENT_APPROVED, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getBusinessErrorMessage())
                .isEqualTo(BusinessErrorMessage.INVALID_STATE_TRANSITION);
    }

    @Test
    void shouldRejectNullComponentsWhenTransitionIsCreated() {
        // Given
        Order order = reserved();
        OrderStatusChange change = order.placementChange();

        // When / Then
        assertThatThrownBy(() -> new OrderTransition(null, order, change)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderTransition(order, null, change)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderTransition(order, order, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullComponentsWhenViewIsCreated() {
        // Given
        Order order = reserved();
        List<Ticket> tickets = List.of();

        // When / Then
        assertThatThrownBy(() -> new OrderView(null, OrderStatus.SOLD, tickets)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderView(order, null, tickets)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCopyTicketsWhenViewIsCreated() {
        // Given
        Order order = reserved();
        List<Ticket> tickets = order.ticketsAt(NOW);

        // When
        OrderView view = new OrderView(order, OrderStatus.RESERVED, tickets);

        // Then
        assertThat(view.tickets()).containsExactlyElementsOf(tickets);
        List<Ticket> copied = view.tickets();
        assertThatThrownBy(copied::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Order reserved() {
        return Order.place(OrderId.newId(), CUSTOMER_ID, EVENT_ID, new Quantity(2), SHARD_ID, NOW, TTL);
    }
}
