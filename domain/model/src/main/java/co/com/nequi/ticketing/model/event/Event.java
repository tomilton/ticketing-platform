package co.com.nequi.ticketing.model.event;

import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Event aggregate: a show with a date, a venue and a fixed capacity split into inventory shards.
 * It is immutable; every change returns a new instance through {@code toBuilder()}.
 */
@Getter
public class Event {

    public static final int NAME_MAX_LENGTH = 200;
    private static final Long INITIAL_VERSION = 1L;

    private final EventId eventId;
    private final String name;
    private final Venue venue;
    private final Instant date;
    private final Capacity capacity;
    private final EventStatus status;
    private final Integer shardCount;
    private final Long version;

    @Builder(toBuilder = true)
    private Event(EventId eventId, String name, Venue venue, Instant date, Capacity capacity,
                  EventStatus status, Integer shardCount, Long version) {
        this.eventId = Objects.requireNonNull(eventId, "Event id must not be null");
        this.name = requireValidName(name);
        this.venue = Objects.requireNonNull(venue, "Venue must not be null");
        this.date = Objects.requireNonNull(date, "Date must not be null");
        this.capacity = Objects.requireNonNull(capacity, "Capacity must not be null");
        this.status = Objects.requireNonNull(status, "Status must not be null");
        this.shardCount = Objects.requireNonNull(shardCount, "Shard count must not be null");
        this.version = Objects.requireNonNull(version, "Version must not be null");
    }

    /**
     * Creates a new active event. The date is truncated to milliseconds and the number of shards
     * never exceeds the capacity. It does not check that the date is in the future.
     *
     * @param eventId    the identifier of the event
     * @param name       the name of the event
     * @param venue      the place of the event
     * @param date       the date of the event
     * @param capacity   the total number of tickets
     * @param shardCount the configured number of inventory shards
     * @return the new event in {@link EventStatus#ACTIVE} with version 1
     */
    public static Event create(EventId eventId, String name, Venue venue, Instant date, Capacity capacity,
                               Integer shardCount) {
        return Event.builder()
                .eventId(eventId)
                .name(name)
                .venue(venue)
                .date(date.truncatedTo(ChronoUnit.MILLIS))
                .capacity(capacity)
                .status(EventStatus.ACTIVE)
                .shardCount(Math.min(shardCount, capacity.value()))
                .version(INITIAL_VERSION)
                .build();
    }

    /**
     * Tells whether the event belongs to the catalog of events on sale.
     *
     * @param now the current instant
     * @return {@code true} if the event is on sale and its date is after {@code now}
     */
    public boolean isListable(Instant now) {
        return status.isOnSale() && date.isAfter(now);
    }

    private static String requireValidName(String name) {
        Objects.requireNonNull(name, "Name must not be null");
        if (name.isBlank() || name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Name must not be blank and must have at most "
                    + NAME_MAX_LENGTH + " characters");
        }
        return name;
    }
}
