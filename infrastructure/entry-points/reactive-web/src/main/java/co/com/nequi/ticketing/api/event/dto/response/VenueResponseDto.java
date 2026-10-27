package co.com.nequi.ticketing.api.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Venue of an event.
 *
 * @param name the name of the venue
 * @param city the city of the venue
 */
@Schema(name = "VenueResponse")
public record VenueResponseDto(String name, String city) {
}
