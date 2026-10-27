package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.support.OrderFixtures;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderTransition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionTransactionBuilderTest {

    private static final Instant AT = OrderFixtures.NOW.plusSeconds(30);

    private final DynamoDbProperties properties = OrderFixtures.properties(10, Duration.ofSeconds(5));
    private final TransitionTransactionBuilder builder = new TransitionTransactionBuilder(properties,
            new AuditItemBuilder(properties));
    private final Order reserved = OrderFixtures.order(OrderFixtures.event(1), 2, 0);
    private final Order pending = reserved.toBuilder().status(OrderStatus.PENDING_CONFIRMATION).version(2L).build();

    @ParameterizedTest
    @CsvSource({
            "RESERVED, PENDING_CONFIRMATION, ' AND expiresAt > :now', 'SET #status = :next, version = :nextVersion'",
            "PENDING_CONFIRMATION, SOLD, ' AND expiresAt > :now', 'SET #status = :next, version = :nextVersion REMOVE activeBucket'",
            "PENDING_CONFIRMATION, EXPIRED, ' AND expiresAt <= :now', 'SET #status = :next, version = :nextVersion REMOVE activeBucket'",
            "RESERVED, CANCELLED, '', 'SET #status = :next, version = :nextVersion REMOVE activeBucket'"})
    void shouldConditionOrderOnStatusVersionAndExpirationWhenStatusChanges(OrderStatus from, OrderStatus to,
                                                                         String expiration, String update) {
        // Given
        Order order = from == OrderStatus.RESERVED ? reserved : pending;

        // When
        Update orderUpdate = items(order.transitionTo(to, StatusChangeReason.PAYMENT_REQUESTED, AT), InventoryMovement.NONE)
                .get(TransitionTransactionBuilder.ORDER_ITEM).update();

        // Then
        assertThat(orderUpdate.tableName()).isEqualTo("orders");
        assertThat(orderUpdate.conditionExpression()).isEqualTo("#status = :expected AND version = :v" + expiration);
        assertThat(orderUpdate.updateExpression()).isEqualTo(update);
        assertThat(orderUpdate.expressionAttributeValues())
                .containsEntry(":expected", AttributeValue.fromS(from.name()))
                .containsEntry(":v", AttributeValue.fromN(String.valueOf(order.getVersion())))
                .containsEntry(":next", AttributeValue.fromS(to.name()))
                .containsEntry(":nextVersion", AttributeValue.fromN(String.valueOf(order.getVersion() + 1)));
        assertThat(orderUpdate.expressionAttributeValues().containsKey(":now")).isEqualTo(!expiration.isEmpty());
    }

    @Test
    void shouldWriteOnlyOrderAndAuditWhenMovementIsNone() {
        // When
        List<TransactWriteItem> items = items(reserved.transitionTo(OrderStatus.PENDING_CONFIRMATION,
                StatusChangeReason.PAYMENT_REQUESTED, AT), InventoryMovement.NONE);

        // Then
        assertThat(items).hasSize(2);
        assertThat(items.get(1).put().tableName()).isEqualTo("order_audit");
        assertThat(items.get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(changeId)");
    }

    @Test
    void shouldSellReservedTicketsOfOrderShardWhenPaymentIsApproved() {
        // When
        List<TransactWriteItem> items = items(pending.transitionTo(OrderStatus.SOLD,
                StatusChangeReason.PAYMENT_APPROVED, AT), InventoryMovement.SELL);

        // Then
        assertThat(items).hasSize(3);
        Update shard = items.get(1).update();
        assertThat(shard.tableName()).isEqualTo("inventory");
        assertThat(shard.conditionExpression()).isEqualTo("reserved >= :q");
        assertThat(shard.updateExpression()).isEqualTo("SET available = available + :available, "
                + "reserved = reserved + :reserved, sold = sold + :sold, version = version + :one");
        assertThat(shard.expressionAttributeValues())
                .containsEntry(":available", AttributeValue.fromN("0"))
                .containsEntry(":reserved", AttributeValue.fromN("-2"))
                .containsEntry(":sold", AttributeValue.fromN("2"))
                .containsEntry(":q", AttributeValue.fromN("2"));
        assertThat(shard.key().get("shardId")).isEqualTo(AttributeValue.fromN("0"));
    }

    @Test
    void shouldReleaseShardAndCustomerTicketsWhenOrderExpires() {
        // When
        List<TransactWriteItem> items = items(pending.transitionTo(OrderStatus.EXPIRED,
                StatusChangeReason.PAYMENT_AFTER_EXPIRY, AT), InventoryMovement.RELEASE);

        // Then
        assertThat(items).hasSize(4);
        assertThat(items.get(1).update().expressionAttributeValues())
                .containsEntry(":available", AttributeValue.fromN("2"))
                .containsEntry(":reserved", AttributeValue.fromN("-2"));
        Update limit = items.get(2).update();
        assertThat(limit.tableName()).isEqualTo("customer_event_limits");
        assertThat(limit.updateExpression()).isEqualTo("ADD ticketsHeld :minusQ");
        assertThat(limit.conditionExpression()).isEqualTo("ticketsHeld >= :q");
        assertThat(limit.expressionAttributeValues())
                .containsEntry(":minusQ", AttributeValue.fromN("-2"))
                .containsEntry(":q", AttributeValue.fromN("2"));
        assertThat(items.get(3).put().item().get("fromStatus")).isEqualTo(AttributeValue.fromS("PENDING_CONFIRMATION"));
        assertThat(items.get(3).put().item().get("actor")).isEqualTo(AttributeValue.fromS("WORKER"));
    }

    private List<TransactWriteItem> items(OrderTransition transition, InventoryMovement movement) {
        return builder.build(transition, movement).transactItems();
    }
}
