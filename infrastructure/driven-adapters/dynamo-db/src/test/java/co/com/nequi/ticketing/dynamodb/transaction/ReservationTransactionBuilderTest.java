package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper;
import co.com.nequi.ticketing.dynamodb.mapper.OrderEntityMapper;
import co.com.nequi.ticketing.dynamodb.support.OrderFixtures;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.order.Order;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.CUSTOMER_ID;
import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.KEY;
import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.LOCK_TOKEN;
import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class ReservationTransactionBuilderTest {

    private final DynamoDbProperties properties = OrderFixtures.properties(10, Duration.ofSeconds(5));
    private final ReservationTransactionBuilder builder = new ReservationTransactionBuilder(properties,
            Mappers.getMapper(OrderEntityMapper.class), new IdempotencyEntityMapper(), new AuditItemBuilder(properties));
    private final Event event = OrderFixtures.event(3);
    private final Order order = OrderFixtures.order(event, 2, 1);

    @Test
    void shouldBuildSixItemsInFixedOrderWithoutClientRequestTokenWhenOrderIsReserved() {
        // When
        TransactWriteItemsRequest request = builder.build(order, event, OrderFixtures.context(), NOW);

        // Then
        assertThat(request.clientRequestToken()).isNull();
        assertThat(request.transactItems()).hasSize(6);
        assertThat(request.transactItems().get(ReservationTransactionBuilder.EVENT_ITEM).conditionCheck()).isNotNull();
        assertThat(request.transactItems().get(ReservationTransactionBuilder.ORDER_ITEM).put().tableName())
                .isEqualTo("orders");
        assertThat(request.transactItems().get(ReservationTransactionBuilder.AUDIT_ITEM).put().tableName())
                .isEqualTo("order_audit");
    }

    @Test
    void shouldCheckThatEventIsActiveAndInFutureWhenOrderIsReserved() {
        // When
        ConditionCheck check = item(ReservationTransactionBuilder.EVENT_ITEM).conditionCheck();

        // Then
        assertThat(check.tableName()).isEqualTo("events");
        assertThat(check.conditionExpression()).isEqualTo("#status = :active AND #date > :nowMillis");
        assertThat(check.expressionAttributeValues()).isEqualTo(Map.of(":active", AttributeValue.fromS("ACTIVE"),
                ":nowMillis", AttributeValue.fromN(String.valueOf(NOW.toEpochMilli()))));
    }

    @Test
    void shouldTakeTicketsOnlyWhenShardHasEnoughWhenOrderIsReserved() {
        // When
        Update update = item(ReservationTransactionBuilder.SHARD_ITEM).update();

        // Then
        assertThat(update.tableName()).isEqualTo("inventory");
        assertThat(update.key()).isEqualTo(Map.of("eventId", AttributeValue.fromS(event.getEventId().value().toString()),
                "shardId", AttributeValue.fromN("1")));
        assertThat(update.updateExpression())
                .isEqualTo("SET available = available - :q, reserved = reserved + :q, version = version + :one");
        assertThat(update.conditionExpression()).isEqualTo("available >= :q");
        assertThat(update.expressionAttributeValues().get(":q")).isEqualTo(AttributeValue.fromN("2"));
    }

    @Test
    void shouldHoldTicketsOfCustomerWithinLimitWhenOrderIsReserved() {
        // When
        Update update = item(ReservationTransactionBuilder.LIMIT_ITEM).update();

        // Then
        assertThat(update.tableName()).isEqualTo("customer_event_limits");
        assertThat(update.key()).isEqualTo(Map.of("limitId",
                AttributeValue.fromS(CUSTOMER_ID.value() + "#" + event.getEventId().value())));
        assertThat(update.updateExpression())
                .isEqualTo("ADD ticketsHeld :q SET expiresAtEpochSeconds = if_not_exists(expiresAtEpochSeconds, :limitTtl)");
        assertThat(update.conditionExpression())
                .isEqualTo("attribute_not_exists(ticketsHeld) OR ticketsHeld <= :maxMinusQ");
        assertThat(update.expressionAttributeValues()).isEqualTo(Map.of(":q", AttributeValue.fromN("2"),
                ":maxMinusQ", AttributeValue.fromN("4"),
                ":limitTtl", AttributeValue.fromN(String.valueOf(OrderFixtures.EVENT_DATE.plus(Duration.ofDays(1))
                        .getEpochSecond()))));
    }

    @Test
    void shouldCreateReservedOrderWithBucketsWhenOrderIsReserved() {
        // When
        Put put = item(ReservationTransactionBuilder.ORDER_ITEM).put();

        // Then
        assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
        assertThat(put.item().get("orderId")).isEqualTo(AttributeValue.fromS(order.getOrderId().value().toString()));
        assertThat(put.item().get("status")).isEqualTo(AttributeValue.fromS("RESERVED"));
        assertThat(put.item().get("shardId")).isEqualTo(AttributeValue.fromN("1"));
        assertThat(put.item().get("expiresAt"))
                .isEqualTo(AttributeValue.fromN(String.valueOf(order.getExpiresAt().toEpochMilli())));
        assertThat(put.item().get("activeBucket").s()).matches("ACTIVE#\\d");
        assertThat(put.item().get("publishBucket").s()).matches("PENDING#\\d");
        assertThat(put.item()).doesNotContainKey("publishedAt");
    }

    @Test
    void shouldRecordPlacementByCustomerWhenOrderIsReserved() {
        // When
        Put put = item(ReservationTransactionBuilder.AUDIT_ITEM).put();

        // Then
        assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(changeId)");
        assertThat(put.item()).isEqualTo(Map.of(
                "orderId", AttributeValue.fromS(order.getOrderId().value().toString()),
                "changeId", AttributeValue.fromS(NOW.toEpochMilli() + "#RESERVED"),
                "toStatus", AttributeValue.fromS("RESERVED"),
                "reason", AttributeValue.fromS("PLACED"),
                "actor", AttributeValue.fromS("CUSTOMER"),
                "at", AttributeValue.fromN(String.valueOf(NOW.toEpochMilli()))));
    }

    @Test
    void shouldCloseKeyWithAcceptedOrderOnlyWhileRequestHoldsItWhenOrderIsReserved() {
        // When
        Update update = item(ReservationTransactionBuilder.KEY_ITEM).update();

        // Then
        assertThat(update.tableName()).isEqualTo("idempotency");
        assertThat(update.key()).isEqualTo(Map.of("idempotencyId",
                AttributeValue.fromS(CUSTOMER_ID.value() + "#" + KEY.value())));
        assertThat(update.conditionExpression()).isEqualTo("#status = :inProgress AND lockToken = :token");
        assertThat(update.updateExpression()).isEqualTo("SET #status = :completed, orderExpiresAt = :orderExpiresAt, "
                + "orderId = :orderId, orderStatus = :orderStatus, outcomeType = :outcomeType");
        assertThat(update.expressionAttributeValues()).containsEntry(":token", AttributeValue.fromS(LOCK_TOKEN.toString()))
                .containsEntry(":completed", AttributeValue.fromS("COMPLETED"))
                .containsEntry(":inProgress", AttributeValue.fromS("IN_PROGRESS"))
                .containsEntry(":outcomeType", AttributeValue.fromS("ACCEPTED"))
                .containsEntry(":orderId", AttributeValue.fromS(order.getOrderId().value().toString()));
    }

    private TransactWriteItem item(int position) {
        List<TransactWriteItem> items = builder.build(order, event, OrderFixtures.context(), NOW).transactItems();
        return items.get(position);
    }
}
