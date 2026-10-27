package co.com.nequi.ticketing.model.inventory;

import lombok.RequiredArgsConstructor;

/**
 * How a change of status of an order moves the counters of its inventory shard. Every movement
 * keeps the counters adding up to the capacity of the shard.
 */
@RequiredArgsConstructor
public enum InventoryMovement {
    /** No change in the shard. */
    NONE(0, 0, 0, 0),
    /** Available tickets become reserved; it needs enough available tickets. */
    RESERVE(-1, 1, 0, 0),
    /** Reserved tickets become sold. */
    SELL(0, -1, 1, 0),
    /** Reserved tickets become available again and stop counting for the limit of the customer. */
    RELEASE(1, -1, 0, 0),
    /** Available tickets are given away; it needs enough available tickets. */
    GRANT(-1, 0, 0, 1);

    private final int availableSign;
    private final int reservedSign;
    private final int soldSign;
    private final int complimentarySign;

    /**
     * Change of the available tickets.
     *
     * @param quantity the tickets of the order
     * @return the signed change of {@code available}
     */
    public Integer availableChange(Integer quantity) {
        return availableSign * quantity;
    }

    /**
     * Change of the reserved tickets.
     *
     * @param quantity the tickets of the order
     * @return the signed change of {@code reserved}
     */
    public Integer reservedChange(Integer quantity) {
        return reservedSign * quantity;
    }

    /**
     * Change of the sold tickets.
     *
     * @param quantity the tickets of the order
     * @return the signed change of {@code sold}
     */
    public Integer soldChange(Integer quantity) {
        return soldSign * quantity;
    }

    /**
     * Change of the complimentary tickets.
     *
     * @param quantity the tickets of the order
     * @return the signed change of {@code complimentary}
     */
    public Integer complimentaryChange(Integer quantity) {
        return complimentarySign * quantity;
    }

    /**
     * Tells whether the movement gives the tickets back to the limit of the customer.
     *
     * @return {@code true} only for {@link #RELEASE}
     */
    public boolean releasesCustomerTickets() {
        return this == RELEASE;
    }
}
