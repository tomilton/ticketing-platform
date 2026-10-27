package co.com.nequi.ticketing.model.inventory;

import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventInventoryTest {

    private static final EventId EVENT_ID = EventId.newId();
    private static final int MAX_SHARDS = 20;

    @Test
    void shouldGiveOneExtraTicketToFirstShardsWhenCapacityIsNotDivisible() {
        // When
        List<InventoryShard> shards = EventInventory.initialShards(EVENT_ID, new Capacity(10), 3);

        // Then
        assertThat(shards).containsExactly(
                new InventoryShard(EVENT_ID, 0, 4, 4, 0, 0, 0, 1L),
                new InventoryShard(EVENT_ID, 1, 3, 3, 0, 0, 0, 1L),
                new InventoryShard(EVENT_ID, 2, 3, 3, 0, 0, 0, 1L));
    }

    @Test
    void shouldCreateOneShardPerTicketWhenShardCountExceedsCapacity() {
        // When
        List<InventoryShard> shards = EventInventory.initialShards(EVENT_ID, new Capacity(5), MAX_SHARDS);

        // Then
        assertThat(shards).hasSize(5).allSatisfy(shard -> assertThat(shard.capacity()).isEqualTo(1));
    }

    @Test
    void shouldSplitEvenlyWhenCapacityIsMaximum() {
        // When
        List<InventoryShard> shards = EventInventory.initialShards(EVENT_ID, new Capacity(Capacity.MAX), MAX_SHARDS);

        // Then
        assertThat(shards).hasSize(MAX_SHARDS).allSatisfy(shard -> assertThat(shard.available()).isEqualTo(50_000));
    }

    @ParameterizedTest
    @MethodSource("shardCountsAndCapacities")
    void shouldKeepCountersEqualToCapacityWhenShardsAreAggregated(Integer shardCount, Integer capacity) {
        // When
        EventInventory inventory = EventInventory.aggregate(EVENT_ID,
                EventInventory.initialShards(EVENT_ID, new Capacity(capacity), shardCount));

        // Then
        assertThat(inventory).isEqualTo(new EventInventory(EVENT_ID, capacity, capacity, 0, 0, 0));
    }

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

    private static Stream<Arguments> shardCountsAndCapacities() {
        return IntStream.rangeClosed(1, MAX_SHARDS).boxed()
                .flatMap(shardCount -> Stream.of(1, 7, Capacity.MAX)
                        .map(capacity -> Arguments.of(shardCount, capacity)));
    }
}
