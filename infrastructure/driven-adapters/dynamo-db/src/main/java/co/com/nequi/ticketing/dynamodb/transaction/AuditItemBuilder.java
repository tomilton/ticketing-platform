package co.com.nequi.ticketing.dynamodb.transaction;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.model.order.OrderStatusChange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the write of one audit record of an order. A record is only ever created, never updated. The
 * identifier of the actor is written only when the change has one.
 */
@Component
@RequiredArgsConstructor
public class AuditItemBuilder {

    static final String NOT_EXISTS_CONDITION = "attribute_not_exists(changeId)";
    private static final String CHANGE_ID_SEPARATOR = "#";

    private final DynamoDbProperties properties;

    /**
     * Builds the conditional put of an audit record.
     *
     * @param change the change of status
     * @return the transaction item that creates the record
     */
    public TransactWriteItem put(OrderStatusChange change) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId", AttributeValue.fromS(change.orderId().value().toString()));
        item.put("changeId", AttributeValue.fromS(change.at().toEpochMilli() + CHANGE_ID_SEPARATOR + change.to().name()));
        Optional.ofNullable(change.from()).ifPresent(from -> item.put("fromStatus", AttributeValue.fromS(from.name())));
        item.put("toStatus", AttributeValue.fromS(change.to().name()));
        item.put("reason", AttributeValue.fromS(change.reason().name()));
        item.put("actor", AttributeValue.fromS(change.actor().name()));
        item.put("at", AttributeValue.fromN(String.valueOf(change.at().toEpochMilli())));
        Optional.ofNullable(change.actorId()).ifPresent(actorId -> item.put("actorId", AttributeValue.fromS(actorId)));
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(properties.orderAuditTable())
                        .item(item)
                        .conditionExpression(NOT_EXISTS_CONDITION)
                        .build())
                .build();
    }
}
