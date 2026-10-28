package co.com.nequi.ticketing.dynamodb.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Table of the notices of the payment gateway, read from the {@code adapters.dynamodb} block.
 *
 * @param webhookEventsTable the table of the received notices
 */
@Validated
@ConfigurationProperties("adapters.dynamodb")
public record WebhookEventsProperties(@NotBlank String webhookEventsTable) {
}
