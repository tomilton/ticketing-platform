package co.com.nequi.ticketing.model.inventory.request;

import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchAvailabilityQueryTest {

    @Test
    void shouldThrowIllegalArgumentExceptionWhenEventIdsAreEmpty() {
        // Given
        List<EventId> eventIds = List.of();

        // When / Then
        assertThatThrownBy(() -> new BatchAvailabilityQuery(eventIds)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepRepeatedIdsInRequestOrderWhenEventIdsAreRepeated() {
        // Given
        EventId first = EventId.newId();
        EventId second = EventId.newId();

        // When
        BatchAvailabilityQuery query = new BatchAvailabilityQuery(List.of(first, first, second));

        // Then
        assertThat(query.eventIds()).containsExactly(first, first, second);
    }
}
