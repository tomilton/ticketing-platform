package co.com.nequi.ticketing.model.order;

import java.util.Objects;

/**
 * Number of complimentary tickets of one grant. The configured limit per grant can be lower than the
 * absolute maximum.
 *
 * @param value the number of tickets, between {@value #MIN} and {@value #MAX_PER_GRANT_LIMIT}
 */
public record ComplimentaryQuantity(Integer value) {

    public static final int MIN = 1;
    public static final int MAX_PER_GRANT_LIMIT = 50;

    public ComplimentaryQuantity {
        Objects.requireNonNull(value, "Complimentary quantity value must not be null");
        if (value < MIN || value > MAX_PER_GRANT_LIMIT) {
            throw new IllegalArgumentException("Complimentary quantity must be between " + MIN + " and "
                    + MAX_PER_GRANT_LIMIT);
        }
    }
}
