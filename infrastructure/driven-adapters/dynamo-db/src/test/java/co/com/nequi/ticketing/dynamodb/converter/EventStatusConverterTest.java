package co.com.nequi.ticketing.dynamodb.converter;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.EventStatus;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStatusConverterTest {

    private final EventStatusConverter converter = new EventStatusConverter();

    @Test
    void shouldWriteNameWhenStatusIsStored() {
        // When / Then
        assertThat(converter.transformFrom(EventStatus.ACTIVE)).isEqualTo(AttributeValue.fromS("ACTIVE"));
    }

    @Test
    void shouldReadStatusWhenNameIsKnown() {
        // When / Then
        assertThat(converter.transformTo(AttributeValue.fromS("ACTIVE"))).isEqualTo(EventStatus.ACTIVE);
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
        assertThat(converter.type()).isEqualTo(EnhancedType.of(EventStatus.class));
        assertThat(converter.attributeValueType()).isEqualTo(AttributeValueType.S);
    }
}
