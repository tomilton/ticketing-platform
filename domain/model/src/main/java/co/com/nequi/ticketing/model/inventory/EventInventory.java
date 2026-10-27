package co.com.nequi.ticketing.model.inventory;

import co.com.nequi.ticketing.model.shared.EventId;

import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;
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

    public EventInventory {
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(capacity, "Capacity must not be null");
        requireCountersAddUpTo(capacity, available, reserved, sold, complimentary);
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
