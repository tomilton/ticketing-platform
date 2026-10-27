package co.com.nequi.ticketing.model.order;

import java.util.Objects;

/**
 * Number of tickets of one order. The configured limit per order can be lower than the absolute
 * maximum.
 *
 * @param value the number of tickets, between {@value #MIN} and {@value #MAX_PER_ORDER_LIMIT}
 */
public record Quantity(Integer value) {

    public static final int MIN = 1;
    public static final int MAX_PER_ORDER_LIMIT = 6;

    public Quantity {
        Objects.requireNonNull(value, "Quantity value must not be null");
        if (value < MIN || value > MAX_PER_ORDER_LIMIT) {
            throw new IllegalArgumentException("Quantity must be between " + MIN + " and " + MAX_PER_ORDER_LIMIT);
        }
    }
}
