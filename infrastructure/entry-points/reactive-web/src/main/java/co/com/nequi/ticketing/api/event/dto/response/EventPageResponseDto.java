package co.com.nequi.ticketing.api.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of the events on sale.
 *
 * @param items      the events, ordered by date
 * @param nextCursor the opaque token of the next page, or {@code null} when there are no more
 */
@Schema(name = "EventPageResponse")
public record EventPageResponseDto(List<EventResponseDto> items, String nextCursor) {
}
