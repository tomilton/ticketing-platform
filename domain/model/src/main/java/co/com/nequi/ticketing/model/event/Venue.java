package co.com.nequi.ticketing.model.event;

import java.util.Objects;

/**
 * Place where an event happens. It is a value object without identity.
 *
 * @param name the name of the venue, not blank and up to {@value #TEXT_MAX_LENGTH} characters
 * @param city the city of the venue, not blank and up to {@value #TEXT_MAX_LENGTH} characters
 */
public record Venue(String name, String city) {

    public static final int TEXT_MAX_LENGTH = 100;

    public Venue {
        requireValidText(name, "Venue name");
        requireValidText(city, "Venue city");
    }

    private static void requireValidText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.length() > TEXT_MAX_LENGTH) {
            throw new IllegalArgumentException(field + " must not be blank and must have at most "
                    + TEXT_MAX_LENGTH + " characters");
        }
    }
}
