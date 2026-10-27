package co.com.nequi.ticketing.model.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIdTest {

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new OrderId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldGenerateDifferentValuesWhenNewIdIsCalledTwice() {
        // When
        OrderId first = OrderId.newId();
        OrderId second = OrderId.newId();

        // Then
        assertThat(first).isNotEqualTo(second);
    }
}
