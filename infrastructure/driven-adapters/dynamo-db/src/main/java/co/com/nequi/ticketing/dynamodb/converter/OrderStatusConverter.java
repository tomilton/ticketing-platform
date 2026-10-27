package co.com.nequi.ticketing.dynamodb.converter;

import co.com.nequi.ticketing.model.enums.OrderStatus;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Arrays;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Stores {@link OrderStatus} by its name. An unknown name is a data error and is never hidden
 * behind a default value.
 */
public class OrderStatusConverter implements AttributeConverter<OrderStatus> {

    @Override
    public AttributeValue transformFrom(OrderStatus status) {
        return AttributeValue.fromS(status.name());
    }

    @Override
    public OrderStatus transformTo(AttributeValue value) {
        return Arrays.stream(OrderStatus.values())
                .filter(status -> status.name().equals(value.s()))
                .findFirst()
                .orElseThrow(DYNAMODB_ERROR::build);
    }

    @Override
    public EnhancedType<OrderStatus> type() {
        return EnhancedType.of(OrderStatus.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
