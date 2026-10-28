package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.OrderId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeaderParserTest {

    private static final UUID VALUE = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void shouldReturnIdentifiersWhenValuesAreUuids() {
        // When / Then
        assertThat(HeaderParser.parseIdempotencyKey(VALUE.toString())).isEqualTo(new IdempotencyKey(VALUE));
        assertThat(HeaderParser.parseOrderId(VALUE.toString())).isEqualTo(new OrderId(VALUE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "1-1-1-1-1"})
    void shouldFailWithMissingIdempotencyKeyWhenKeyIsNotUuid(String value) {
        // When / Then
        assertThatThrownBy(() -> HeaderParser.parseIdempotencyKey(value))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.MISSING_IDEMPOTENCY_KEY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " 11111111-1111-4111-8111-111111111111", "not-a-uuid", "1-1-1-1-1"})
    void shouldFailWithValidationErrorNamingParameterWhenOrderIdIsNotUuid(String value) {
        // When / Then
        assertThatThrownBy(() -> HeaderParser.parseOrderId(value))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("orderId");
    }
}
