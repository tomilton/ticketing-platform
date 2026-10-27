package co.com.nequi.ticketing.dynamodb.converter;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStatusConverterTest {

    private final OrderStatusConverter converter = new OrderStatusConverter();

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void shouldKeepStatusWhenItIsWrittenAndReadByName(OrderStatus status) {
        // When
        AttributeValue stored = converter.transformFrom(status);

        // Then
        assertThat(stored).isEqualTo(AttributeValue.fromS(status.name()));
        assertThat(converter.transformTo(stored)).isEqualTo(status);
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenNameIsUnknown() {
        // Given
        AttributeValue unknown = AttributeValue.fromS("UNKNOWN");

        // When / Then
        assertThatThrownBy(() -> converter.transformTo(unknown))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }

    @Test
    void shouldDescribeStringAttributeWhenTypeIsRequested() {
        // When / Then
        assertThat(converter.type()).isEqualTo(EnhancedType.of(OrderStatus.class));
        assertThat(converter.attributeValueType()).isEqualTo(AttributeValueType.S);
    }
}
