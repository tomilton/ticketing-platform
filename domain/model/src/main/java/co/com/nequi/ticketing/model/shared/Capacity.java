package co.com.nequi.ticketing.model.shared;

import java.util.Objects;

/**
 * Total number of tickets of an event. It never changes after the event is created.
 *
 * @param value the number of tickets, between {@value #MIN} and {@value #MAX}
 */
public record Capacity(Integer value) {

    public static final int MIN = 1;
    public static final int MAX = 1_000_000;

    public Capacity {
        Objects.requireNonNull(value, "Capacity value must not be null");
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException("Capacity must be between " + MIN + " and " + MAX);
        }
    }
}
