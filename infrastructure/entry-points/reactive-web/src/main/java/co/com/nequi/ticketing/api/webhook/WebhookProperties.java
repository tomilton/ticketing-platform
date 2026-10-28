package co.com.nequi.ticketing.api.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Verification of the notices of the payment gateway, read from the {@code webhook} block.
 *
 * @param secret    the HMAC secret agreed with the gateway; never logged
 * @param tolerance how far the timestamp of a notice may be from now, in either direction
 */
@Validated
@ConfigurationProperties("webhook")
public record WebhookProperties(@NotBlank String secret, @NotNull Duration tolerance) {

    private static final String HIDDEN = "****";

    @Override
    public String toString() {
        return "WebhookProperties[secret=" + HIDDEN + ", tolerance=" + tolerance + "]";
    }
}
