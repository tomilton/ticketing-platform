package co.com.nequi.ticketing.api.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Accepted order returned by {@code POST /orders}.
 *
 * @param orderId   the identifier of the order
 * @param status    the status of the order, RESERVED
 * @param expiresAt the end of the reservation in UTC
 */
@Schema(name = "PlaceOrderResponse")
public record PlaceOrderResponseDto(UUID orderId, String status, Instant expiresAt) {
}
