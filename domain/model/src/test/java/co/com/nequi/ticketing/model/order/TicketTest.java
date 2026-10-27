package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.enums.TicketStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    @Test
    void shouldThrowNullPointerExceptionWhenComponentIsNull() {
        // Given
        TicketId ticketId = TicketId.newId();

        // When / Then
        assertThatThrownBy(() -> new Ticket(null, TicketStatus.SOLD)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Ticket(ticketId, null)).isInstanceOf(NullPointerException.class);
    }
}
