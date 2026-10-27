package co.com.nequi.ticketing.api.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Venue of a new event.
 *
 * @param name the name of the venue
 * @param city the city of the venue
 */
@Schema(name = "VenueRequest")
public record VenueRequestDto(@NotBlank @Size(max = VenueRequestDto.TEXT_MAX_LENGTH) String name,
                              @NotBlank @Size(max = VenueRequestDto.TEXT_MAX_LENGTH) String city) {

    static final int TEXT_MAX_LENGTH = 100;
}
