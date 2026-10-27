package co.com.nequi.ticketing.sqs.listener.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the SQS listener, read from the {@code adapters.sqs.listener} block.
 *
 * @param region           the AWS region
 * @param endpoint         the endpoint override, only for the local environment; {@code null} otherwise
 * @param orderPlacedQueue the name of the queue of accepted orders
 * @param orderPlacedDlq   the name of its dead letter queue
 * @param waitTime         the long polling time of each receive, at most {@value #MAX_WAIT_SECONDS} seconds
 * @param maxMessages      the messages of each receive, between {@value #MIN_MESSAGES} and {@value #MAX_MESSAGES}
 * @param maxConcurrency   the messages processed at the same time, at least {@value #MIN_CONCURRENCY}
 */
@Validated
@ConfigurationProperties("adapters.sqs.listener")
public record SqsListenerProperties(@NotBlank String region,
                                    String endpoint,
                                    @NotBlank String orderPlacedQueue,
                                    @NotBlank String orderPlacedDlq,
                                    Duration waitTime,
                                    Integer maxMessages,
                                    Integer maxConcurrency) {

    public static final int MAX_WAIT_SECONDS = 20;
    public static final int MIN_MESSAGES = 1;
    public static final int MAX_MESSAGES = 10;
    public static final int MIN_CONCURRENCY = 1;

    public SqsListenerProperties {
        Objects.requireNonNull(waitTime, "Wait time must not be null");
        Objects.requireNonNull(maxMessages, "Max messages must not be null");
        Objects.requireNonNull(maxConcurrency, "Max concurrency must not be null");
        if (waitTime.isNegative() || waitTime.toSeconds() > MAX_WAIT_SECONDS) {
            throw new IllegalArgumentException("Wait time must be between 0 and " + MAX_WAIT_SECONDS + " seconds");
        }
        if (maxMessages < MIN_MESSAGES || maxMessages > MAX_MESSAGES) {
            throw new IllegalArgumentException("Max messages must be between " + MIN_MESSAGES + " and " + MAX_MESSAGES);
        }
        if (maxConcurrency < MIN_CONCURRENCY) {
            throw new IllegalArgumentException("Max concurrency must be at least " + MIN_CONCURRENCY);
        }
    }
}
