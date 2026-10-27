package co.com.nequi.ticketing.model.exception;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void shouldKeepMessageWhenBuiltWithoutCause() {
        // When
        BusinessException exception = BusinessErrorMessage.EVENT_NOT_FOUND.build();

        // Then
        assertThat(exception).hasMessage("Event not found").hasNoCause();
        assertThat(exception.getBusinessErrorMessage()).isEqualTo(BusinessErrorMessage.EVENT_NOT_FOUND);
    }

    @Test
    void shouldKeepMessageAndCauseWhenBuiltWithCause() {
        // Given
        IllegalStateException cause = new IllegalStateException("boom");

        // When
        BusinessException exception = BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE.build(cause);

        // Then
        assertThat(exception).hasMessage("Event date must be in the future").hasCause(cause);
        assertThat(exception.getBusinessErrorMessage()).isEqualTo(BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE);
    }
}
