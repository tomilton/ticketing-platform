package co.com.nequi.ticketing.model.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketIdTest {

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new TicketId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldGenerateDifferentValuesWhenNewIdIsCalledTwice() {
        // When
        TicketId first = TicketId.newId();
        TicketId second = TicketId.newId();

        // Then
        assertThat(first).isNotEqualTo(second);
    }
}
