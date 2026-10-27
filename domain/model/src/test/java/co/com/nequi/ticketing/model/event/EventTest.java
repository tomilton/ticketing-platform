package co.com.nequi.ticketing.model.event;

import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final EventId EVENT_ID = EventId.newId();
    private static final String NAME = "Rock al Parque";
    private static final Venue VENUE = new Venue("Parque Simon Bolivar", "Bogota");
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant DATE = Instant.parse("2026-12-01T20:00:00.123456789Z");

    @Test
    void shouldCreateActiveEventWithVersionOneAndTruncatedDateWhenCreateIsCalled() {
        // When
        Event event = Event.create(EVENT_ID, NAME, VENUE, DATE, new Capacity(500), 1);

        // Then
        assertThat(event.getEventId()).isEqualTo(EVENT_ID);
        assertThat(event.getName()).isEqualTo(NAME);
        assertThat(event.getVenue()).isEqualTo(VENUE);
        assertThat(event.getDate()).isEqualTo(Instant.parse("2026-12-01T20:00:00.123Z"));
        assertThat(event.getCapacity()).isEqualTo(new Capacity(500));
        assertThat(event.getStatus()).isEqualTo(EventStatus.ACTIVE);
        assertThat(event.getShardCount()).isEqualTo(1);
        assertThat(event.getVersion()).isEqualTo(1L);
    }

    @ParameterizedTest
    @CsvSource({"5, 20, 5", "500, 1, 1", "10, 3, 3"})
    void shouldLimitShardCountToCapacityWhenCreateIsCalled(Integer capacity, Integer shardCount, Integer expected) {
        // When
        Event event = Event.create(EVENT_ID, NAME, VENUE, DATE, new Capacity(capacity), shardCount);

        // Then
        assertThat(event.getShardCount()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void shouldThrowIllegalArgumentExceptionWhenNameIsBlank(String name) {
        // Given
        Capacity capacity = new Capacity(1);

        // When / Then
        assertThatThrownBy(() -> Event.create(EVENT_ID, name, VENUE, DATE, capacity, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenNameHas201Characters() {
        // Given
        String name = "a".repeat(Event.NAME_MAX_LENGTH + 1);
        Capacity capacity = new Capacity(1);

        // When / Then
        assertThatThrownBy(() -> Event.create(EVENT_ID, name, VENUE, DATE, capacity, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldBeListableWhenEventIsActiveAndDateIsAfterNow() {
        // Given
        Event event = Event.create(EVENT_ID, NAME, VENUE, DATE, new Capacity(1), 1);

        // When / Then
        assertThat(event.isListable(NOW)).isTrue();
    }

    @Test
    void shouldNotBeListableWhenDateIsEqualOrBeforeNow() {
        // Given
        Event event = Event.create(EVENT_ID, NAME, VENUE, NOW, new Capacity(1), 1);

        // When / Then
        assertThat(event.isListable(NOW)).isFalse();
        assertThat(event.isListable(NOW.plusMillis(1))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"CANCELLED", "FINISHED"})
    void shouldNotBeListableWhenStatusIsFinal(EventStatus status) {
        // Given
        Event event = Event.create(EVENT_ID, NAME, VENUE, DATE, new Capacity(1), 1).toBuilder()
                .status(status)
                .build();

        // When / Then
        assertThat(event.isListable(NOW)).isFalse();
    }
}
