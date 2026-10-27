package co.com.nequi.ticketing.model.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventIdTest {

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new EventId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldGenerateDifferentValuesWhenNewIdIsCalledTwice() {
        // When
        EventId first = EventId.newId();
        EventId second = EventId.newId();

        // Then
        assertThat(first).isNotEqualTo(second);
    }
}
