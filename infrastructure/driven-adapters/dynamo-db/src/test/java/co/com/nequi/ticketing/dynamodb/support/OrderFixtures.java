package co.com.nequi.ticketing.dynamodb.support;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Orders, events and settings shared by the unit tests of the order adapters.
 */
@UtilityClass
public class OrderFixtures {

    public static final Instant NOW = Instant.parse("2026-10-27T10:00:00.123Z");
    public static final Duration TTL = Duration.ofMinutes(10);
    public static final Instant EVENT_DATE = Instant.parse("2026-12-01T20:00:00Z");
    public static final CustomerId CUSTOMER_ID = new CustomerId(UUID.fromString("0b6c7f3e-8a2d-4f51-9c3e-1d2a3b4c5d6e"));
    public static final IdempotencyKey KEY = new IdempotencyKey(UUID.fromString("7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"));
    public static final UUID LOCK_TOKEN = UUID.fromString("5c4d3e2f-1a0b-4c9d-8e7f-6a5b4c3d2e1f");

    public static Event event(Integer shardCount) {
        return Event.create(new EventId(UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f")), "Rock al Parque",
                new Venue("Parque Simon Bolivar", "Bogota"), EVENT_DATE, new Capacity(50), shardCount);
    }

    public static Order order(Event event, Integer quantity, Integer shardId) {
        return Order.place(OrderId.newId(), CUSTOMER_ID, event.getEventId(), new Quantity(quantity), shardId, NOW, TTL);
    }

    public static ReservationContext context() {
        return new ReservationContext(CUSTOMER_ID, KEY, LOCK_TOKEN, 6);
    }

    public static DynamoDbProperties properties(Integer conflictRetries, Duration reserveTimeout) {
        return new DynamoDbProperties("us-east-1", null, "events", "inventory", "by-status-date",
                Duration.ofSeconds(2), Duration.ofMillis(500), 2, Duration.ofSeconds(1), "orders", "order_audit",
                "idempotency", "customer_event_limits", "expiring-reservations", "pending-publication",
                conflictRetries, reserveTimeout);
    }
}
