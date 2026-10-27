package co.com.nequi.ticketing.commons.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalExceptionTest {

    @Test
    void shouldKeepMessageWhenBuiltWithoutCause() {
        // When
        TechnicalException exception = TechnicalErrorMessage.VALIDATION_ERROR.build();

        // Then
        assertThat(exception).hasMessage("Error while validating data").hasNoCause();
        assertThat(exception.getErrorMessage()).isEqualTo(TechnicalErrorMessage.VALIDATION_ERROR);
    }

    @Test
    void shouldKeepMessageAndCauseWhenBuiltWithCause() {
        // Given
        IllegalStateException cause = new IllegalStateException("timeout");

        // When
        TechnicalException exception = TechnicalErrorMessage.DYNAMODB_ERROR.build(cause);

        // Then
        assertThat(exception).hasMessage("Database error").hasCause(cause);
        assertThat(exception.getErrorMessage()).isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }
}
