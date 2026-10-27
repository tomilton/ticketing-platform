package co.com.nequi.ticketing.sqs.commons.dto;

import java.util.UUID;

/**
 * Identifiers of the accepted order carried by the {@code OrderPlaced} message.
 *
 * @param orderId  the order, the only value the consumer decides on
 * @param eventId  the event of the order, for logs and metrics
 * @param quantity the tickets of the order, for logs and metrics
 */
public record OrderPlacedDataDto(UUID orderId, UUID eventId, Integer quantity) {
}
