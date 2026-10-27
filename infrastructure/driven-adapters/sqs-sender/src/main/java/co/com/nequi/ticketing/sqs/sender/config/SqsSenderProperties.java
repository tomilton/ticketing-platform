package co.com.nequi.ticketing.sqs.sender.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the SQS publisher, read from the {@code adapters.sqs.sender} block.
 *
 * @param region           the AWS region
 * @param endpoint         the endpoint override, only for the local environment; {@code null} otherwise
 * @param orderPlacedQueue the name of the queue of accepted orders
 * @param sendTimeout      the maximum time of one publication
 */
@Validated
@ConfigurationProperties("adapters.sqs.sender")
public record SqsSenderProperties(@NotBlank String region,
                                  String endpoint,
                                  @NotBlank String orderPlacedQueue,
                                  @NotNull Duration sendTimeout) {

    public SqsSenderProperties {
        Objects.requireNonNull(sendTimeout, "Send timeout must not be null");
        if (sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("Send timeout must be positive");
        }
    }
}
