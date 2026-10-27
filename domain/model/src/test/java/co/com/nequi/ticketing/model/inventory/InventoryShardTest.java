package co.com.nequi.ticketing.model.inventory;

import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryShardTest {

    private static final EventId EVENT_ID = EventId.newId();

    @Test
    void shouldThrowIllegalArgumentExceptionWhenCountersDoNotAddUpToCapacity() {
        // When / Then
        assertThatThrownBy(() -> new InventoryShard(EVENT_ID, 0, 10, 5, 1, 1, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenCounterIsNegative() {
        // When / Then
        assertThatThrownBy(() -> new InventoryShard(EVENT_ID, 0, 10, -1, 11, 0, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenShardIdIsNegative() {
        // When / Then
        assertThatThrownBy(() -> new InventoryShard(EVENT_ID, -1, 1, 1, 0, 0, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateShardWhenCountersAddUpToCapacity() {
        // When
        InventoryShard shard = new InventoryShard(EVENT_ID, 2, 10, 4, 3, 2, 1, 7L);

        // Then
        assertThat(shard).isEqualTo(new InventoryShard(EVENT_ID, 2, 10, 4, 3, 2, 1, 7L));
    }
}
