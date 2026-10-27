package co.com.nequi.ticketing.model.success;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuccessMessageTest {

    @Test
    void shouldFormatCodeAndMessageWhenToStringIsCalled() {
        // When / Then
        assertThat(SuccessMessage.SUCCESSFUL).hasToString("TKS0001 - Successful");
        assertThat(SuccessMessage.CREATED).hasToString("TKS0002 - Created");
    }

    @Test
    void shouldUseCodeTks0003WhenOrderIsAccepted() {
        // When / Then
        assertThat(SuccessMessage.ACCEPTED.getCode()).isEqualTo("TKS0003");
        assertThat(SuccessMessage.ACCEPTED.getMessage()).isEqualTo("Accepted");
        assertThat(SuccessMessage.ACCEPTED).hasToString("TKS0003 - Accepted");
    }
}
