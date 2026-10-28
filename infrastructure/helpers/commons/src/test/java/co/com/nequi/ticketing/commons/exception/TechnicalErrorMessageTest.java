package co.com.nequi.ticketing.commons.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalErrorMessageTest {

    @ParameterizedTest
    @CsvSource({
            "VALIDATION_ERROR, TKT0001, false",
            "INVALID_JSON, TKT0002, false",
            "EMPTY_REQUEST_BODY, TKT0003, false",
            "MISSING_IDEMPOTENCY_KEY, TKT0004, false",
            "DYNAMODB_ERROR, TKT0005, true",
            "SQS_ERROR, TKT0006, true",
            "PAYMENT_GATEWAY_ERROR, TKT0007, true",
            "INVALID_MESSAGE, TKT0008, false",
            "UNEXPECTED_ERROR, TKT0009, true",
            "UNAUTHORIZED, TKT0010, false",
            "FORBIDDEN, TKT0011, false",
            "RATE_LIMITED, TKT0012, false",
            "INVALID_WEBHOOK_SIGNATURE, TKT0013, false",
            "PAYLOAD_TOO_LARGE, TKT0014, false"
    })
    void shouldExposeCodeAndRetryableWhenMessageIsRead(TechnicalErrorMessage message, String code,
                                                       Boolean retryable) {
        // When / Then
        assertThat(message.getCode()).isEqualTo(code);
        assertThat(message.getRetryable()).isEqualTo(retryable);
    }

    @Test
    void shouldFormatCodeAndMessageWhenToStringIsCalled() {
        // When / Then
        assertThat(TechnicalErrorMessage.DYNAMODB_ERROR).hasToString("TKT0005 - Database error");
    }
}
