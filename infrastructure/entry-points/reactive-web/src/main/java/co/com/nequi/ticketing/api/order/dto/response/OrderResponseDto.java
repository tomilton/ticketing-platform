package co.com.nequi.ticketing.api.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Order returned by {@code GET /orders/{orderId}}, with its status at the instant of the query.
 *
 * @param orderId   the identifier of the order
 * @param eventId   the event of the order
 * @param quantity  the number of tickets
 * @param status    RESERVED, PENDING_CONFIRMATION, SOLD, CANCELLED or EXPIRED
 * @param expiresAt the end of the reservation in UTC
 * @param tickets   the tickets with their status
 */
@Schema(name = "OrderResponse")
public record OrderResponseDto(UUID orderId, UUID eventId, Integer quantity, String status, Instant expiresAt,
                               List<TicketResponseDto> tickets) {
}
