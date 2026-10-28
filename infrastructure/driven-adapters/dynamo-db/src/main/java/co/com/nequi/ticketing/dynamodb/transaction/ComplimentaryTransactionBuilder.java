package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.OrderEntityMapper;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.order.GrantContext;
import co.com.nequi.ticketing.model.order.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder.ORDER_NOT_EXISTS_CONDITION;
import static co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder.ORDER_SCHEMA;

/**
 * Builds the single transaction that grants a complimentary order: it checks the event, moves the
 * tickets of one shard from available to complimentary, creates the final order with its audit
 * record and closes the idempotency key of the administrator. The order has no index bucket, so
 * neither the sweep nor the republication ever reads it. The event and the shard keep the positions
 * of the reservation, so the failures are translated by the same indexes.
 */
@Component
@RequiredArgsConstructor
public class ComplimentaryTransactionBuilder {

    public static final int GRANT_KEY_ITEM = 4;
    static final String GRANT_TICKETS_UPDATE = "SET available = available - :q, "
            + "complimentary = complimentary + :q, version = version + :one";
    private static final String ONE = "1";

    private final DynamoDbProperties properties;
    private final OrderEntityMapper orderMapper;
    private final AuditItemBuilder auditItemBuilder;
    private final ReservationTransactionBuilder reservationBuilder;

    /**
     * Builds the grant of a complimentary order on its shard.
     *
     * @param order   the new complimentary order, with the shard to take the tickets from
     * @param event   the event of the order
     * @param context the administrator and the idempotency key
     * @param now     the current instant
     * @return the transaction with its five items
     */
    public TransactWriteItemsRequest build(Order order, Event event, GrantContext context, Instant now) {
        return TransactWriteItemsRequest.builder()
                .transactItems(List.of(
                        ReservationTransactionBuilder.checkEventOnSale(properties.eventsTable(), event, now),
                        grantTickets(order),
                        createOrder(order),
                        auditItemBuilder.put(order.grantChange(context.administratorId())),
                        reservationBuilder.closeKey(order, context.scope(), context.key(), context.lockToken())))
                .build();
    }

    private TransactWriteItem grantTickets(Order order) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(properties.inventoryTable())
                        .key(ReservationTransactionBuilder.shardKey(order))
                        .updateExpression(GRANT_TICKETS_UPDATE)
                        .conditionExpression(ReservationTransactionBuilder.ENOUGH_TICKETS_CONDITION)
                        .expressionAttributeValues(Map.of(
                                ":q", AttributeValue.fromN(String.valueOf(order.getQuantity())),
                                ":one", AttributeValue.fromN(ONE)))
                        .build())
                .build();
    }

    private TransactWriteItem createOrder(Order order) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(properties.ordersTable())
                        .item(ORDER_SCHEMA.itemToMap(orderMapper.toEntity(order), true))
                        .conditionExpression(ORDER_NOT_EXISTS_CONDITION)
                        .build())
                .build();
    }
}
