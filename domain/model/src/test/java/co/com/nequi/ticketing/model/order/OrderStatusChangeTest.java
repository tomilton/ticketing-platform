package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.ChangeActor;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStatusChangeTest {

    private static final OrderId ORDER_ID = OrderId.newId();
    private static final Instant AT = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void shouldAllowMissingPreviousStatusWhenOrderIsCreated() {
        // When
        OrderStatusChange change = new OrderStatusChange(ORDER_ID, null, OrderStatus.RESERVED,
                StatusChangeReason.PLACED, ChangeActor.CUSTOMER, AT);

        // Then
        assertThat(change.from()).isNull();
        assertThat(change.to()).isEqualTo(OrderStatus.RESERVED);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenRequiredComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new OrderStatusChange(null, null, OrderStatus.RESERVED, StatusChangeReason.PLACED,
                ChangeActor.CUSTOMER, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderStatusChange(ORDER_ID, null, null, StatusChangeReason.PLACED,
                ChangeActor.CUSTOMER, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderStatusChange(ORDER_ID, null, OrderStatus.RESERVED, null,
                ChangeActor.CUSTOMER, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderStatusChange(ORDER_ID, null, OrderStatus.RESERVED,
                StatusChangeReason.PLACED, null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderStatusChange(ORDER_ID, null, OrderStatus.RESERVED,
                StatusChangeReason.PLACED, ChangeActor.CUSTOMER, null)).isInstanceOf(NullPointerException.class);
    }
}
