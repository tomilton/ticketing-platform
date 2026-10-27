package co.com.nequi.ticketing.api.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Body of {@code POST /events}.
 *
 * @param name     the name of the event
 * @param venue    the venue of the event
 * @param date     the date, ISO-8601 with an explicit offset
 * @param capacity the total number of tickets
 */
@Schema(name = "CreateEventRequest")
public record CreateEventRequestDto(@NotBlank @Size(max = CreateEventRequestDto.NAME_MAX_LENGTH) String name,
                                    @NotNull @Valid VenueRequestDto venue,
                                    @NotNull Instant date,
                                    @NotNull @Min(CreateEventRequestDto.MIN_CAPACITY)
                                    @Max(CreateEventRequestDto.MAX_CAPACITY) Integer capacity) {

    static final int NAME_MAX_LENGTH = 200;
    static final long MIN_CAPACITY = 1;
    static final long MAX_CAPACITY = 1_000_000;
}
