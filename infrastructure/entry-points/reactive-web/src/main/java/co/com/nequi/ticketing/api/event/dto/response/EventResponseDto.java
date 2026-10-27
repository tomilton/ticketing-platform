package co.com.nequi.ticketing.api.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Event returned by the API.
 *
 * @param eventId  the identifier
 * @param name     the name
 * @param venue    the venue
 * @param date     the date in UTC
 * @param capacity the total number of tickets
 * @param status   ACTIVE, CANCELLED or FINISHED
 */
@Schema(name = "EventResponse")
public record EventResponseDto(UUID eventId, String name, VenueResponseDto venue, Instant date, Integer capacity,
                               String status) {
}
