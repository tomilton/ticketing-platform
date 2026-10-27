package co.com.nequi.ticketing.api.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One ticket of an order.
 *
 * @param ticketId the identifier of the ticket
 * @param status   AVAILABLE, RESERVED, PENDING_CONFIRMATION, SOLD or COMPLIMENTARY
 */
@Schema(name = "TicketResponse")
public record TicketResponseDto(UUID ticketId, String status) {
}
