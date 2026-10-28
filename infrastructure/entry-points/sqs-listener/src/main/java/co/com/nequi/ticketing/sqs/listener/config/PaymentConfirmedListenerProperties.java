package co.com.nequi.ticketing.sqs.listener.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Queues of the notices of the payment gateway, read from the {@code adapters.sqs.listener} block.
 * The receive settings are those of {@link SqsListenerProperties}.
 *
 * @param paymentConfirmedQueue the name of the queue of the notices
 * @param paymentConfirmedDlq   the name of its dead letter queue
 */
@Validated
@ConfigurationProperties("adapters.sqs.listener")
public record PaymentConfirmedListenerProperties(@NotBlank String paymentConfirmedQueue,
                                                 @NotBlank String paymentConfirmedDlq) {
}
