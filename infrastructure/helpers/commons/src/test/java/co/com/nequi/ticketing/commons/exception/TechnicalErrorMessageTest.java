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
            "DYNAMODB_ERROR, TKT0005, true",
            "UNEXPECTED_ERROR, TKT0009, true"
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
