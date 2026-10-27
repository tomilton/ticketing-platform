package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.order.request.GetOrderQuery;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRequestsTest {

    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final IdempotencyKey KEY = new IdempotencyKey(UUID.randomUUID());
    private static final EventId EVENT_ID = EventId.newId();
    private static final OrderId ORDER_ID = OrderId.newId();
    private static final Quantity QUANTITY = new Quantity(2);
    private static final UUID TOKEN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void shouldThrowNullPointerExceptionWhenCommandComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new PlaceOrderCommand(null, KEY, EVENT_ID, QUANTITY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlaceOrderCommand(CUSTOMER_ID, null, EVENT_ID, QUANTITY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlaceOrderCommand(CUSTOMER_ID, KEY, null, QUANTITY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlaceOrderCommand(CUSTOMER_ID, KEY, EVENT_ID, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenQueryComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new GetOrderQuery(null, ORDER_ID)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GetOrderQuery(CUSTOMER_ID, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenMessageComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new OrderPlacedMessage(null, ORDER_ID, EVENT_ID, QUANTITY, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderPlacedMessage(TOKEN, null, EVENT_ID, QUANTITY, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderPlacedMessage(TOKEN, ORDER_ID, null, QUANTITY, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderPlacedMessage(TOKEN, ORDER_ID, EVENT_ID, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderPlacedMessage(TOKEN, ORDER_ID, EVENT_ID, QUANTITY, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenReservationContextComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new ReservationContext(null, KEY, TOKEN, 6)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationContext(CUSTOMER_ID, null, TOKEN, 6)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationContext(CUSTOMER_ID, KEY, null, 6)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationContext(CUSTOMER_ID, KEY, TOKEN, null)).isInstanceOf(NullPointerException.class);
    }
}
