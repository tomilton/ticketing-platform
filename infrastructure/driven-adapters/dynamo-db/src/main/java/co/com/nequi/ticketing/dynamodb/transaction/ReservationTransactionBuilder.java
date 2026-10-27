package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.entity.OrderEntity;
import co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper;
import co.com.nequi.ticketing.dynamodb.mapper.OrderEntityMapper;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.ReservationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Builds the single transaction that reserves an order: it checks the event, takes the tickets from
 * one shard, raises the limit of the customer, creates the order with its audit record and closes
 * the idempotency key. The position of each item is fixed, because the cancellation reasons come in
 * the same order.
 */
@Component
@RequiredArgsConstructor
public class ReservationTransactionBuilder {

    public static final TableSchema<OrderEntity> ORDER_SCHEMA = TableSchema.fromBean(OrderEntity.class);
    public static final int EVENT_ITEM = 0;
    public static final int SHARD_ITEM = 1;
    public static final int LIMIT_ITEM = 2;
    public static final int ORDER_ITEM = 3;
    public static final int AUDIT_ITEM = 4;
    public static final int KEY_ITEM = 5;
    public static final String ACTIVE_BUCKET_PREFIX = "ACTIVE#";
    public static final String PUBLISH_BUCKET_PREFIX = "PENDING#";
    public static final int BUCKETS = 10;

    static final String EVENT_ON_SALE_CONDITION = "#status = :active AND #date > :nowMillis";
    static final String TAKE_TICKETS_UPDATE = "SET available = available - :q, reserved = reserved + :q, "
            + "version = version + :one";
    static final String ENOUGH_TICKETS_CONDITION = "available >= :q";
    static final String HOLD_TICKETS_UPDATE = "ADD ticketsHeld :q "
            + "SET expiresAtEpochSeconds = if_not_exists(expiresAtEpochSeconds, :limitTtl)";
    static final String LIMIT_CONDITION = "attribute_not_exists(ticketsHeld) OR ticketsHeld <= :maxMinusQ";
    static final String ORDER_NOT_EXISTS_CONDITION = "attribute_not_exists(orderId)";
    static final String KEY_HELD_CONDITION = "#status = :inProgress AND lockToken = :token";
    private static final Duration LIMIT_RETENTION_AFTER_EVENT = Duration.ofDays(1);
    private static final String ONE = "1";

    private final DynamoDbProperties properties;
    private final OrderEntityMapper orderMapper;
    private final IdempotencyEntityMapper idempotencyMapper;
    private final AuditItemBuilder auditItemBuilder;

    /**
     * Builds the reservation of an order on its shard.
     *
     * @param order   the new order, with the shard to take the tickets from
     * @param event   the event of the order
     * @param context the limit of the customer and the idempotency key
     * @param now     the current instant
     * @return the transaction with its six items
     */
    public TransactWriteItemsRequest build(Order order, Event event, ReservationContext context, Instant now) {
        String quantity = String.valueOf(order.getQuantity());
        return TransactWriteItemsRequest.builder()
                .transactItems(List.of(
                        checkEventOnSale(properties.eventsTable(), event, now),
                        takeTickets(order, quantity),
                        holdTicketsOfCustomer(order, event, context, quantity),
                        createOrder(order),
                        auditItemBuilder.put(order.placementChange()),
                        closeKey(order, context)))
                .build();
    }

    /**
     * Key of the limit of a customer for an event.
     *
     * @param order the order of the customer
     * @return the key of the limit item
     */
    public static Map<String, AttributeValue> limitKey(Order order) {
        return Map.of("limitId", AttributeValue.fromS(order.getCustomerId().value() + "#" + order.getEventId().value()));
    }

    /**
     * Key of the inventory shard of an order.
     *
     * @param order the order
     * @return the key of the shard item
     */
    public static Map<String, AttributeValue> shardKey(Order order) {
        return Map.of("eventId", AttributeValue.fromS(order.getEventId().value().toString()),
                "shardId", AttributeValue.fromN(String.valueOf(order.getShardId())));
    }

    /**
     * Check that the event is active and has not started, shared by the writes that take tickets.
     *
     * @param eventsTable the table of events
     * @param event       the event
     * @param now         the current instant
     * @return the condition check item
     */
    static TransactWriteItem checkEventOnSale(String eventsTable, Event event, Instant now) {
        return TransactWriteItem.builder()
                .conditionCheck(ConditionCheck.builder()
                        .tableName(eventsTable)
                        .key(Map.of("eventId", AttributeValue.fromS(event.getEventId().value().toString())))
                        .conditionExpression(EVENT_ON_SALE_CONDITION)
                        .expressionAttributeNames(Map.of("#status", "status", "#date", "date"))
                        .expressionAttributeValues(Map.of(
                                ":active", AttributeValue.fromS(EventStatus.ACTIVE.name()),
                                ":nowMillis", AttributeValue.fromN(String.valueOf(now.toEpochMilli()))))
                        .build())
                .build();
    }

    private TransactWriteItem takeTickets(Order order, String quantity) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.inventoryTable())
                        .key(shardKey(order))
                        .updateExpression(TAKE_TICKETS_UPDATE)
                        .conditionExpression(ENOUGH_TICKETS_CONDITION)
                        .expressionAttributeValues(Map.of(
                                ":q", AttributeValue.fromN(quantity),
                                ":one", AttributeValue.fromN(ONE)))
                        .build())
                .build();
    }

    private TransactWriteItem holdTicketsOfCustomer(Order order, Event event, ReservationContext context,
                                                    String quantity) {
        long limitTtl = event.getDate().plus(LIMIT_RETENTION_AFTER_EVENT).getEpochSecond();
        int maxMinusQuantity = context.maxTicketsPerCustomer() - order.getQuantity();
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.customerLimitsTable())
                        .key(limitKey(order))
                        .updateExpression(HOLD_TICKETS_UPDATE)
                        .conditionExpression(LIMIT_CONDITION)
                        .expressionAttributeValues(Map.of(
                                ":q", AttributeValue.fromN(quantity),
                                ":limitTtl", AttributeValue.fromN(String.valueOf(limitTtl)),
                                ":maxMinusQ", AttributeValue.fromN(String.valueOf(maxMinusQuantity))))
                        .build())
                .build();
    }

    private TransactWriteItem createOrder(Order order) {
        OrderEntity entity = orderMapper.toEntity(order);
        entity.setActiveBucket(ACTIVE_BUCKET_PREFIX + ThreadLocalRandom.current().nextInt(BUCKETS));
        entity.setPublishBucket(PUBLISH_BUCKET_PREFIX + ThreadLocalRandom.current().nextInt(BUCKETS));
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(properties.ordersTable())
                        .item(ORDER_SCHEMA.itemToMap(entity, true))
                        .conditionExpression(ORDER_NOT_EXISTS_CONDITION)
                        .build())
                .build();
    }

    private TransactWriteItem closeKey(Order order, ReservationContext context) {
        Map<String, AttributeValue> outcome = idempotencyMapper.outcomeAttributes(PlacementOutcome.Accepted.of(order));
        Map<String, AttributeValue> values = new HashMap<>();
        outcome.forEach((name, value) -> values.put(":" + name, value));
        values.put(":completed", AttributeValue.fromS(IdempotencyStatus.COMPLETED.name()));
        values.put(":inProgress", AttributeValue.fromS(IdempotencyStatus.IN_PROGRESS.name()));
        values.put(":token", AttributeValue.fromS(context.lockToken().toString()));
        String setOutcome = outcome.keySet().stream().sorted().map(name -> name + " = :" + name)
                .collect(Collectors.joining(", "));
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.idempotencyTable())
                        .key(Map.of(IdempotencyEntityMapper.IDEMPOTENCY_ID,
                                AttributeValue.fromS(idempotencyMapper.idempotencyId(context.customerId(), context.key()))))
                        .updateExpression("SET #status = :completed, " + setOutcome)
                        .conditionExpression(KEY_HELD_CONDITION)
                        .expressionAttributeNames(Map.of("#status", IdempotencyEntityMapper.STATUS))
                        .expressionAttributeValues(values)
                        .build())
                .build();
    }
}
