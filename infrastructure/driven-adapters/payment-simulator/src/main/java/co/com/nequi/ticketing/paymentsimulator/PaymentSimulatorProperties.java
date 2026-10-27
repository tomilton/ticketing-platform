package co.com.nequi.ticketing.paymentsimulator;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the payment simulator, read from the {@code adapters.payment} block.
 *
 * @param mode         the answer of the simulated gateway to every charge
 * @param latency      the simulated delay of each call; zero or positive
 * @param timeout      the maximum time of a call; a slower call is a technical error
 * @param approvalRate the share of orders approved in {@link Mode#RANDOM}, from 0 to 1
 * @param slowLatency  the delay of a charge in {@link Mode#SLOW}; greater than the timeout
 */
@Validated
@ConfigurationProperties("adapters.payment")
public record PaymentSimulatorProperties(@NotNull Mode mode, @NotNull Duration latency, @NotNull Duration timeout,
                                         @NotNull Double approvalRate, @NotNull Duration slowLatency) {

    private static final Double MINIMUM_APPROVAL_RATE = 0.0;
    private static final Double MAXIMUM_APPROVAL_RATE = 1.0;

    public PaymentSimulatorProperties {
        Objects.requireNonNull(mode, "Mode must not be null");
        Objects.requireNonNull(latency, "Latency must not be null");
        Objects.requireNonNull(timeout, "Timeout must not be null");
        Objects.requireNonNull(approvalRate, "Approval rate must not be null");
        Objects.requireNonNull(slowLatency, "Slow latency must not be null");
        if (latency.isNegative() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Latency must not be negative and timeout must be positive");
        }
        if (approvalRate < MINIMUM_APPROVAL_RATE || approvalRate > MAXIMUM_APPROVAL_RATE) {
            throw new IllegalArgumentException("Approval rate must be between 0 and 1");
        }
        if (slowLatency.compareTo(timeout) <= 0) {
            throw new IllegalArgumentException("Slow latency must be greater than the timeout");
        }
    }

    /**
     * Answer of the simulated gateway.
     */
    public enum Mode {
        /** Every charge is approved. */
        APPROVE,
        /** Every charge is declined. */
        DECLINE,
        /** Each order is approved with the approval rate, always with the same answer for the same order. */
        RANDOM,
        /** Every charge answers after the slow latency, so it always exceeds the timeout. */
        SLOW,
        /** Every charge fails as an unavailable gateway. */
        DOWN
    }
}
