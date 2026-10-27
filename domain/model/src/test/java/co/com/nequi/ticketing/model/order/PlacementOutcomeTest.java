package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKETS_SOLD_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlacementOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void shouldTakeIdentifierStatusAndExpirationWhenAcceptedIsBuiltFromOrder() {
        // Given
        Order order = Order.place(OrderId.newId(), new CustomerId(UUID.randomUUID()), EventId.newId(),
                new Quantity(1), 0, NOW, Duration.ofMinutes(10));

        // When
        PlacementOutcome.Accepted accepted = PlacementOutcome.Accepted.of(order);

        // Then
        assertThat(accepted).isEqualTo(new PlacementOutcome.Accepted(order.getOrderId(), OrderStatus.RESERVED,
                NOW.plus(Duration.ofMinutes(10))));
    }

    @Test
    void shouldDescribeEveryVariantWhenSwitchIsExhaustive() {
        // Given
        PlacementOutcome accepted = new PlacementOutcome.Accepted(OrderId.newId(), OrderStatus.RESERVED, NOW);
        PlacementOutcome rejected = new PlacementOutcome.Rejected(TICKETS_SOLD_OUT);

        // When / Then
        assertThat(describe(accepted)).isEqualTo("ACCEPTED");
        assertThat(describe(rejected)).isEqualTo("TKB0003");
    }

    @Test
    void shouldThrowNullPointerExceptionWhenComponentIsNull() {
        // Given
        OrderId orderId = OrderId.newId();

        // When / Then
        assertThatThrownBy(() -> new PlacementOutcome.Accepted(null, OrderStatus.RESERVED, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlacementOutcome.Accepted(orderId, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlacementOutcome.Accepted(orderId, OrderStatus.RESERVED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlacementOutcome.Rejected(null)).isInstanceOf(NullPointerException.class);
    }

    private static String describe(PlacementOutcome outcome) {
        return switch (outcome) {
            case PlacementOutcome.Accepted accepted -> accepted.status() == OrderStatus.RESERVED ? "ACCEPTED" : "OTHER";
            case PlacementOutcome.Rejected rejected -> rejected.error().getCode();
        };
    }
}
