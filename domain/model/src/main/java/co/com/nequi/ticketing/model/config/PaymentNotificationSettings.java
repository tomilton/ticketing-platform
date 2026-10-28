package co.com.nequi.ticketing.model.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Business parameters of the notices of the payment gateway.
 *
 * @param retention how long a received notice is remembered to drop its copies; positive
 */
public record PaymentNotificationSettings(Duration retention) {

    public PaymentNotificationSettings {
        Objects.requireNonNull(retention, "Retention must not be null");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Retention must be positive");
        }
    }
}
