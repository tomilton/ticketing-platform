package co.com.nequi.ticketing.sqs.commons.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of the {@code PaymentConfirmed} message.
 *
 * @param eventId    the identifier the gateway gave to the notice
 * @param type       always {@code PaymentConfirmed}
 * @param version    the version of the contract
 * @param orderId    the order that was charged
 * @param status     the result of the charge, {@code APPROVED} or {@code DECLINED}
 * @param occurredAt when the gateway decided the result
 */
public record PaymentConfirmedPayloadDto(UUID eventId, String type, Integer version, UUID orderId, String status,
                                         Instant occurredAt) {
}
