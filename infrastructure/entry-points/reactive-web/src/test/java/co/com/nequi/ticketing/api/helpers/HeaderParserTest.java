package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.shared.CustomerId;
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
        assertThat(HeaderParser.parseCustomerId(VALUE.toString())).isEqualTo(new CustomerId(VALUE));
        assertThat(HeaderParser.parseIdempotencyKey(VALUE.toString())).isEqualTo(new IdempotencyKey(VALUE));
        assertThat(HeaderParser.parseOrderId(VALUE.toString())).isEqualTo(new OrderId(VALUE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " 11111111-1111-4111-8111-111111111111", "abc", "1-1-1-1-1"})
    void shouldFailWithValidationErrorNamingHeaderWhenCustomerIdIsNotUuid(String value) {
        // When / Then
        assertThatThrownBy(() -> HeaderParser.parseCustomerId(value))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("X-Customer-Id");
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

    @Test
    void shouldFailWithValidationErrorNamingParameterWhenOrderIdIsNotUuid() {
        // When / Then
        assertThatThrownBy(() -> HeaderParser.parseOrderId("not-a-uuid"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("orderId");
    }
}
