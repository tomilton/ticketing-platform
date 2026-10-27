package co.com.nequi.ticketing.model.event.command;

import java.time.Instant;
import java.util.Objects;

/**
 * Request to create an event with its inventory.
 *
 * @param name      the name of the event
 * @param venueName the name of the venue
 * @param city      the city of the venue
 * @param date      the date of the event
 * @param capacity  the total number of tickets
 */
public record CreateEventCommand(String name, String venueName, String city, Instant date, Integer capacity) {

    public CreateEventCommand {
        Objects.requireNonNull(name, "Name must not be null");
        Objects.requireNonNull(venueName, "Venue name must not be null");
        Objects.requireNonNull(city, "City must not be null");
        Objects.requireNonNull(date, "Date must not be null");
        Objects.requireNonNull(capacity, "Capacity must not be null");
    }
}
