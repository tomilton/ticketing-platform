package co.com.nequi.ticketing.paymentsimulator;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the payment simulator, read from the {@code adapters.payment} block.
 *
 * @param mode    the answer of the simulated gateway to every charge
 * @param latency the simulated delay of each call; zero or positive
 * @param timeout the maximum time of a call; a slower call is a technical error
 */
@Validated
@ConfigurationProperties("adapters.payment")
public record PaymentSimulatorProperties(@NotNull Mode mode, @NotNull Duration latency, @NotNull Duration timeout) {

    public PaymentSimulatorProperties {
        Objects.requireNonNull(mode, "Mode must not be null");
        Objects.requireNonNull(latency, "Latency must not be null");
        Objects.requireNonNull(timeout, "Timeout must not be null");
        if (latency.isNegative() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Latency must not be negative and timeout must be positive");
        }
    }

    /**
     * Answer of the simulated gateway.
     */
    public enum Mode {
        APPROVE,
        DECLINE
    }
}
