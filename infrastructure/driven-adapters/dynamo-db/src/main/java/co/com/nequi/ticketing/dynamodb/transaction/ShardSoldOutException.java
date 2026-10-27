package co.com.nequi.ticketing.dynamodb.transaction;

/**
 * Signal that the tried shard has not enough tickets, so the reservation moves to the next shard.
 * It never leaves the adapter.
 */
public class ShardSoldOutException extends RuntimeException {

    public ShardSoldOutException() {
        super("Inventory shard has not enough tickets");
    }
}
