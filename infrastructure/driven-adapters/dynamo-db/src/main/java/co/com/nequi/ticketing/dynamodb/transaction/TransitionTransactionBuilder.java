package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderTransition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the transaction of a change of status: the order only if it still has the status and
 * version that were read, the movement of its inventory shard, the release of the tickets held by
 * the customer and the audit record. The order is always the first item.
 */
@Component
@RequiredArgsConstructor
public class TransitionTransactionBuilder {

    public static final int ORDER_ITEM = 0;

    static final String ORDER_CONDITION = "#status = :expected AND version = :v";
    static final String VALID_RESERVATION_CONDITION = " AND expiresAt > :now";
    static final String ENDED_RESERVATION_CONDITION = " AND expiresAt <= :now";
    static final String ORDER_UPDATE = "SET #status = :next, version = :nextVersion";
    static final String REMOVE_ACTIVE_BUCKET = " REMOVE activeBucket";
    static final String SHARD_UPDATE = "SET available = available + :available, reserved = reserved + :reserved, "
            + "sold = sold + :sold, version = version + :one";
    static final String SHARD_CONDITION = "reserved >= :q";
    static final String RELEASE_UPDATE = "ADD ticketsHeld :minusQ";
    static final String RELEASE_CONDITION = "ticketsHeld >= :q";
    private static final String ONE = "1";

    private final DynamoDbProperties properties;
    private final AuditItemBuilder auditItemBuilder;

    /**
     * Builds the transaction of a change of status.
     *
     * @param transition the change of status
     * @param movement   how the change moves the inventory shard
     * @return the transaction, with the order as its first item
     */
    public TransactWriteItemsRequest build(OrderTransition transition, InventoryMovement movement) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(updateOrder(transition));
        if (movement != InventoryMovement.NONE) {
            items.add(moveShard(transition.next(), movement));
        }
        if (movement.releasesCustomerTickets()) {
            items.add(releaseCustomerTickets(transition.next()));
        }
        items.add(auditItemBuilder.put(transition.change()));
        return TransactWriteItemsRequest.builder().transactItems(items).build();
    }

    private TransactWriteItem updateOrder(OrderTransition transition) {
        Order previous = transition.previous();
        OrderStatus target = transition.next().getStatus();
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":expected", AttributeValue.fromS(previous.getStatus().name()),
                ":v", number(previous.getVersion()),
                ":next", AttributeValue.fromS(target.name()),
                ":nextVersion", number(transition.next().getVersion())));
        String expiration = expirationCondition(target);
        if (!expiration.isEmpty()) {
            values.put(":now", number(transition.change().at().toEpochMilli()));
        }
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.ordersTable())
                        .key(Map.of("orderId", AttributeValue.fromS(previous.getOrderId().value().toString())))
                        .updateExpression(ORDER_UPDATE + (target.isFinal() ? REMOVE_ACTIVE_BUCKET : ""))
                        .conditionExpression(ORDER_CONDITION + expiration)
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(values)
                        .build())
                .build();
    }

    private static String expirationCondition(OrderStatus target) {
        return switch (target) {
            case PENDING_CONFIRMATION, SOLD -> VALID_RESERVATION_CONDITION;
            case EXPIRED -> ENDED_RESERVATION_CONDITION;
            case RESERVED, CANCELLED -> "";
        };
    }

    private TransactWriteItem moveShard(Order order, InventoryMovement movement) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.inventoryTable())
                        .key(ReservationTransactionBuilder.shardKey(order))
                        .updateExpression(SHARD_UPDATE)
                        .conditionExpression(SHARD_CONDITION)
                        .expressionAttributeValues(Map.of(
                                ":available", number(movement.availableChange(order.getQuantity())),
                                ":reserved", number(movement.reservedChange(order.getQuantity())),
                                ":sold", number(movement.soldChange(order.getQuantity())),
                                ":q", number(order.getQuantity().value()),
                                ":one", AttributeValue.fromN(ONE)))
                        .build())
                .build();
    }

    private TransactWriteItem releaseCustomerTickets(Order order) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.customerLimitsTable())
                        .key(ReservationTransactionBuilder.limitKey(order))
                        .updateExpression(RELEASE_UPDATE)
                        .conditionExpression(RELEASE_CONDITION)
                        .expressionAttributeValues(Map.ofEntries(
                                Map.entry(":minusQ", number(-order.getQuantity().value())),
                                Map.entry(":q", number(order.getQuantity().value()))))
                        .build())
                .build();
    }

    private static AttributeValue number(Number value) {
        return AttributeValue.fromN(String.valueOf(value));
    }
}
