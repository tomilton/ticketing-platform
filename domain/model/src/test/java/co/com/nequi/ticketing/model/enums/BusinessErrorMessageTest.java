package co.com.nequi.ticketing.model.enums;

import co.com.nequi.ticketing.model.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessErrorMessageTest {

    @Test
    void shouldFormatCodeAndMessageWhenToStringIsCalled() {
        // When / Then
        assertThat(BusinessErrorMessage.EVENT_NOT_FOUND).hasToString("TKB0001 - Event not found");
    }

    @Test
    void shouldUseCodeTkb0010WhenEventDateIsNotInFuture() {
        // When / Then
        assertThat(BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE.getCode()).isEqualTo("TKB0010");
    }

    @ParameterizedTest
    @CsvSource({
            "ORDER_NOT_FOUND, TKB0002, Order not found",
            "TICKETS_SOLD_OUT, TKB0003, Tickets sold out",
            "TICKET_LIMIT_EXCEEDED, TKB0004, Ticket limit exceeded",
            "IDEMPOTENCY_KEY_REUSED, TKB0005, Idempotency key reused with a different request",
            "REQUEST_IN_PROGRESS, TKB0006, The original request is still in progress",
            "EVENT_NOT_ACTIVE, TKB0007, Event is not on sale",
            "INVALID_STATE_TRANSITION, TKB0009, Invalid order state transition"})
    void shouldExposeCodeMessageAndExceptionWhenOrderErrorIsUsed(BusinessErrorMessage error, String code,
                                                                 String message) {
        // When
        BusinessException exception = error.build();

        // Then
        assertThat(error.getCode()).isEqualTo(code);
        assertThat(error.getMessage()).isEqualTo(message);
        assertThat(error).hasToString(code + " - " + message);
        assertThat(exception.getBusinessErrorMessage()).isEqualTo(error);
        assertThat(exception.getMessage()).isEqualTo(message);
    }
}
