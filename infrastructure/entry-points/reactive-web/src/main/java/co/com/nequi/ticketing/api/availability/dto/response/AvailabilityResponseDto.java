package co.com.nequi.ticketing.api.availability.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Ticket counters of an event; available + reserved + sold + complimentary = capacity.
 *
 * @param eventId       the event
 * @param capacity      the total number of tickets
 * @param available     the tickets that can be reserved
 * @param reserved      the tickets held by a pending order
 * @param sold          the tickets paid
 * @param complimentary the tickets given away
 */
@Schema(name = "AvailabilityResponse")
public record AvailabilityResponseDto(UUID eventId, Integer capacity, Integer available, Integer reserved,
                                      Integer sold, Integer complimentary) {
}
