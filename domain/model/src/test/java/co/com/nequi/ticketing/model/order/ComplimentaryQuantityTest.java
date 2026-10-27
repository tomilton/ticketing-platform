package co.com.nequi.ticketing.model.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplimentaryQuantityTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void shouldKeepValueWhenValueIsWithinRange(Integer value) {
        // When / Then
        assertThat(new ComplimentaryQuantity(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 51})
    void shouldThrowIllegalArgumentExceptionWhenValueIsOutOfRange(Integer value) {
        // When / Then
        assertThatThrownBy(() -> new ComplimentaryQuantity(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new ComplimentaryQuantity(null)).isInstanceOf(NullPointerException.class);
    }
}
