package co.com.nequi.ticketing.model.inventory;

import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;

import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Availability of an event: the sum of all its inventory shards. Its counters always add up to
 * its capacity, and two inventories with the same values are equal.
 *
 * @param eventId       the event
 * @param capacity      the total number of tickets
 * @param available     the tickets that can be reserved
 * @param reserved      the tickets held by a pending order
 * @param sold          the tickets paid
 * @param complimentary the tickets given away
 */
public record EventInventory(EventId eventId, Integer capacity, Integer available, Integer reserved,
                             Integer sold, Integer complimentary) {

    private static final Long INITIAL_VERSION = 1L;
    private static final Integer EMPTY = 0;

    public EventInventory {
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(capacity, "Capacity must not be null");
        requireCountersAddUpTo(capacity, available, reserved, sold, complimentary);
    }

    /**
     * Splits the capacity of a new event into shards. Each shard gets the same share and the first
     * ones get one extra ticket when the capacity is not divisible. Every ticket starts available.
     *
     * @param eventId    the event
     * @param capacity   the total number of tickets
     * @param shardCount the configured number of shards; it is limited to the capacity
     * @return the shards ordered by shard id
     */
    public static List<InventoryShard> initialShards(EventId eventId, Capacity capacity, Integer shardCount) {
        int total = capacity.value();
        int shards = Math.min(shardCount, total);
        int share = total / shards;
        int remainder = total % shards;
        return IntStream.range(0, shards)
                .mapToObj(shardId -> {
                    int shardCapacity = shardId < remainder ? share + 1 : share;
                    return new InventoryShard(eventId, shardId, shardCapacity, shardCapacity,
                            EMPTY, EMPTY, EMPTY, INITIAL_VERSION);
                })
                .toList();
    }

    /**
     * Adds up the shards of an event.
     *
     * @param eventId the event
     * @param shards  the shards of the event; it must not be empty
     * @return the aggregated inventory
     * @throws IllegalArgumentException if there are no shards or a shard belongs to another event
     */
    public static EventInventory aggregate(EventId eventId, List<InventoryShard> shards) {
        if (shards.isEmpty() || shards.stream().anyMatch(shard -> !shard.eventId().equals(eventId))) {
            throw new IllegalArgumentException("Shards must not be empty and must belong to the event");
        }
        return new EventInventory(eventId,
                sum(shards, InventoryShard::capacity),
                sum(shards, InventoryShard::available),
                sum(shards, InventoryShard::reserved),
                sum(shards, InventoryShard::sold),
                sum(shards, InventoryShard::complimentary));
    }

    static void requireCountersAddUpTo(Integer capacity, Integer... counters) {
        Stream.of(counters).forEach(counter -> Objects.requireNonNull(counter, "Counter must not be null"));
        boolean valid = Stream.of(counters).allMatch(counter -> counter >= 0)
                && Stream.of(counters).mapToInt(Integer::intValue).sum() == capacity;
        if (!valid) {
            throw new IllegalArgumentException("Counters must be non negative and add up to the capacity");
        }
    }

    private static Integer sum(List<InventoryShard> shards, ToIntFunction<InventoryShard> counter) {
        return shards.stream().mapToInt(counter).sum();
    }
}
