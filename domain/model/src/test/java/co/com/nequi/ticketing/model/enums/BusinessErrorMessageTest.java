package co.com.nequi.ticketing.model.enums;

import org.junit.jupiter.api.Test;

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
}
