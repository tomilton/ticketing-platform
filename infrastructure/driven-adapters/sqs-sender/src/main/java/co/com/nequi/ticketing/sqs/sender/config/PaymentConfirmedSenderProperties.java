package co.com.nequi.ticketing.sqs.sender.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Queue of the notices of the payment gateway, read from the {@code adapters.sqs.sender} block.
 *
 * @param paymentConfirmedQueue the name of the queue the worker consumes
 * @param sendTimeout           the maximum time of one publication
 */
@Validated
@ConfigurationProperties("adapters.sqs.sender")
public record PaymentConfirmedSenderProperties(@NotBlank String paymentConfirmedQueue,
                                               @NotNull Duration sendTimeout) {
}
