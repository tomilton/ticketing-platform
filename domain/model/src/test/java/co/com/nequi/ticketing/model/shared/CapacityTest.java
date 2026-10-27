package co.com.nequi.ticketing.model.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapacityTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1_000_001})
    void shouldThrowIllegalArgumentExceptionWhenValueIsOutOfRange(Integer value) {
        // When / Then
        assertThatThrownBy(() -> new Capacity(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1_000_000})
    void shouldKeepValueWhenValueIsWithinRange(Integer value) {
        // When
        Capacity capacity = new Capacity(value);

        // Then
        assertThat(capacity.value()).isEqualTo(value);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new Capacity(null)).isInstanceOf(NullPointerException.class);
    }
}
