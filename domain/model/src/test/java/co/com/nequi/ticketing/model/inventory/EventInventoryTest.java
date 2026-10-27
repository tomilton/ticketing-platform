package co.com.nequi.ticketing.model.inventory;

import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventInventoryTest {

    private static final EventId EVENT_ID = EventId.newId();

    @Test
    void shouldAddEveryCounterWhenShardsAreAggregated() {
        // Given
        List<InventoryShard> shards = List.of(
                new InventoryShard(EVENT_ID, 0, 10, 4, 3, 2, 1, 2L),
                new InventoryShard(EVENT_ID, 1, 5, 1, 1, 1, 2, 3L));

        // When
        EventInventory inventory = EventInventory.aggregate(EVENT_ID, shards);

        // Then
        assertThat(inventory).isEqualTo(new EventInventory(EVENT_ID, 15, 5, 4, 3, 3));
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenShardsAreEmpty() {
        // Given
        List<InventoryShard> shards = List.of();

        // When / Then
        assertThatThrownBy(() -> EventInventory.aggregate(EVENT_ID, shards))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenShardBelongsToAnotherEvent() {
        // Given
        List<InventoryShard> shards = List.of(new InventoryShard(EventId.newId(), 0, 1, 1, 0, 0, 0, 1L));

        // When / Then
        assertThatThrownBy(() -> EventInventory.aggregate(EVENT_ID, shards))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenCountersDoNotAddUpToCapacity() {
        // When / Then
        assertThatThrownBy(() -> new EventInventory(EVENT_ID, 10, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
