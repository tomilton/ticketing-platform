package co.com.nequi.ticketing.model.order.command;

import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrantComplimentaryCommandTest {

    private static final EventId EVENT_ID = EventId.newId();
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final ComplimentaryQuantity QUANTITY = new ComplimentaryQuantity(3);

    @Test
    void shouldKeepValuesWhenComponentsAreValid() {
        // When
        GrantComplimentaryCommand command = new GrantComplimentaryCommand(EVENT_ID, CUSTOMER_ID, QUANTITY);

        // Then
        assertThat(command.quantity()).isEqualTo(new ComplimentaryQuantity(3));
    }

    @Test
    void shouldThrowNullPointerExceptionWhenComponentIsNull() {
        // When / Then
        assertThatThrownBy(() -> new GrantComplimentaryCommand(null, CUSTOMER_ID, QUANTITY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GrantComplimentaryCommand(EVENT_ID, null, QUANTITY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GrantComplimentaryCommand(EVENT_ID, CUSTOMER_ID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
