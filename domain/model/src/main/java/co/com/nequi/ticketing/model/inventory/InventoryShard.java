package co.com.nequi.ticketing.model.inventory;

import co.com.nequi.ticketing.model.shared.EventId;

import java.util.Objects;

/**
 * One portion of the inventory of an event. Splitting the inventory spreads concurrent writes.
 * Its counters always add up to its capacity.
 *
 * @param eventId       the event the shard belongs to
 * @param shardId       the position of the shard, starting at zero
 * @param capacity      the number of tickets of the shard
 * @param available     the tickets that can be reserved
 * @param reserved      the tickets held by a pending order
 * @param sold          the tickets paid
 * @param complimentary the tickets given away
 * @param version       the optimistic locking version, starting at one
 */
public record InventoryShard(EventId eventId, Integer shardId, Integer capacity, Integer available,
                             Integer reserved, Integer sold, Integer complimentary, Long version) {

    public InventoryShard {
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(shardId, "Shard id must not be null");
        Objects.requireNonNull(capacity, "Capacity must not be null");
        Objects.requireNonNull(version, "Version must not be null");
        if (shardId < 0 || capacity < 1 || version < 1) {
            throw new IllegalArgumentException("Shard id, capacity or version out of range");
        }
        EventInventory.requireCountersAddUpTo(capacity, available, reserved, sold, complimentary);
    }
}
