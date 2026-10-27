package co.com.nequi.ticketing.model.event.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateEventCommandTest {

    private static final Instant DATE = Instant.parse("2026-12-01T20:00:00Z");

    @Test
    void shouldThrowNullPointerExceptionWhenAnyFieldIsNull() {
        // When / Then
        assertThatThrownBy(() -> new CreateEventCommand(null, "Venue", "City", DATE, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateEventCommand("Name", null, "City", DATE, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateEventCommand("Name", "Venue", null, DATE, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateEventCommand("Name", "Venue", "City", null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateEventCommand("Name", "Venue", "City", DATE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldKeepValuesWhenAllFieldsArePresent() {
        // When
        CreateEventCommand command = new CreateEventCommand("Name", "Venue", "City", DATE, 1);

        // Then
        assertThat(command).isEqualTo(new CreateEventCommand("Name", "Venue", "City", DATE, 1));
    }
}
