package co.com.nequi.ticketing.model.idempotency;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new IdempotencyKey(null)).isInstanceOf(NullPointerException.class);
    }
}
