package co.com.nequi.ticketing.model.config;

import java.util.Objects;

/**
 * Business parameters of the inventory.
 *
 * @param shardCount the number of shards of a new event, between {@value #MIN_SHARDS} and {@value #MAX_SHARDS}
 */
public record InventorySettings(Integer shardCount) {

    public static final int MIN_SHARDS = 1;
    public static final int MAX_SHARDS = 20;

    public InventorySettings {
        Objects.requireNonNull(shardCount, "Shard count must not be null");
        if (shardCount < MIN_SHARDS || shardCount > MAX_SHARDS) {
            throw new IllegalArgumentException("Shard count must be between " + MIN_SHARDS + " and " + MAX_SHARDS);
        }
    }
}
