package co.com.nequi.ticketing.api.webhook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * Body of a notice of the payment gateway.
 *
 * @param eventId    the identifier the gateway gives to the notice
 * @param orderId    the order that was charged
 * @param status     the result of the charge, {@code APPROVED} or {@code DECLINED}
 * @param occurredAt when the gateway decided the result
 */
@Schema(name = "PaymentNotificationRequest")
public record PaymentNotificationRequestDto(@NotNull UUID eventId,
                                            @NotNull UUID orderId,
                                            @NotNull @Pattern(regexp = "APPROVED|DECLINED") String status,
                                            @NotNull Instant occurredAt) {
}
