package co.com.nequi.ticketing.sqs.commons.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of the {@code OrderPlaced} message.
 *
 * @param messageId  the identifier of this publication
 * @param type       always {@code OrderPlaced}
 * @param version    the version of the contract
 * @param occurredAt when the message was published
 * @param data       the identifiers of the order
 */
public record OrderPlacedPayloadDto(UUID messageId, String type, Integer version, Instant occurredAt,
                                    OrderPlacedDataDto data) {
}
