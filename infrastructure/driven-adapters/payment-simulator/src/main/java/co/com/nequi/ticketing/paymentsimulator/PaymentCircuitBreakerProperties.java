package co.com.nequi.ticketing.paymentsimulator;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Circuit breaker of the payment gateway, read from the {@code adapters.payment.circuit-breaker} block.
 *
 * @param failureRateThreshold the share of failed calls, in percent, that opens the circuit
 * @param slidingWindowSize    the number of recent calls the failure rate is measured on
 * @param minimumCalls         the calls needed before the failure rate is measured
 * @param waitInOpen           how long the circuit stays open before it lets test calls through
 * @param halfOpenCalls        the test calls that decide whether the circuit closes again
 */
@Validated
@ConfigurationProperties("adapters.payment.circuit-breaker")
public record PaymentCircuitBreakerProperties(@NotNull Float failureRateThreshold,
                                              @NotNull Integer slidingWindowSize,
                                              @NotNull Integer minimumCalls,
                                              @NotNull Duration waitInOpen,
                                              @NotNull Integer halfOpenCalls) {

    private static final Float MAXIMUM_PERCENT = 100.0F;

    public PaymentCircuitBreakerProperties {
        Objects.requireNonNull(failureRateThreshold, "Failure rate threshold must not be null");
        Objects.requireNonNull(slidingWindowSize, "Sliding window size must not be null");
        Objects.requireNonNull(minimumCalls, "Minimum calls must not be null");
        Objects.requireNonNull(waitInOpen, "Wait in open state must not be null");
        Objects.requireNonNull(halfOpenCalls, "Half open calls must not be null");
        if (failureRateThreshold <= 0 || failureRateThreshold > MAXIMUM_PERCENT) {
            throw new IllegalArgumentException("Failure rate threshold must be greater than 0 and at most 100");
        }
        if (slidingWindowSize <= 0 || minimumCalls <= 0 || halfOpenCalls <= 0
                || waitInOpen.isZero() || waitInOpen.isNegative()) {
            throw new IllegalArgumentException("Window, calls and wait in open state must be positive");
        }
    }
}
