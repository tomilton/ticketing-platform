package co.com.nequi.ticketing.model.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerIdTest {

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new CustomerId(null)).isInstanceOf(NullPointerException.class);
    }
}
