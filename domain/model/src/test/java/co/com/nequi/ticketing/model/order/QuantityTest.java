package co.com.nequi.ticketing.model.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuantityTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 6})
    void shouldKeepValueWhenValueIsWithinRange(Integer value) {
        // When / Then
        assertThat(new Quantity(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 7})
    void shouldThrowIllegalArgumentExceptionWhenValueIsOutOfRange(Integer value) {
        // When / Then
        assertThatThrownBy(() -> new Quantity(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new Quantity(null)).isInstanceOf(NullPointerException.class);
    }
}
