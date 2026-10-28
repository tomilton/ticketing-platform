package co.com.nequi.ticketing.api.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Tells whether a notice comes from the payment gateway: the signature must be the hexadecimal
 * HMAC-SHA256 of {@code timestamp + "." + body} with the agreed secret, compared in constant time,
 * and the timestamp, in epoch seconds, must be within the tolerance of now. Neither the secret nor
 * the signatures are ever logged.
 */
@Component
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] SEPARATOR = ".".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final Duration tolerance;

    public WebhookSignatureVerifier(WebhookProperties properties) {
        this.key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.tolerance = properties.tolerance();
    }

    /**
     * Verifies a notice.
     *
     * @param timestamp the {@code X-Timestamp} header, in epoch seconds
     * @param signature the {@code X-Signature} header
     * @param body      the raw body of the request
     * @param now       the current instant
     * @return {@code true} only if the signature is valid and the timestamp is recent
     */
    public Boolean isAuthentic(String timestamp, String signature, byte[] body, Instant now) {
        return parseEpochSeconds(timestamp)
                .filter(sent -> !Duration.between(sent, now).abs().minus(tolerance).isPositive())
                .map(sent -> MessageDigest.isEqual(sign(timestamp, body),
                        Optional.ofNullable(signature).orElse("").toLowerCase(Locale.ROOT)
                                .getBytes(StandardCharsets.UTF_8)))
                .orElse(Boolean.FALSE);
    }

    private byte[] sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(SEPARATOR);
            return HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("The platform does not provide " + ALGORITHM, error);
        }
    }

    private static Optional<Instant> parseEpochSeconds(String timestamp) {
        try {
            return Optional.ofNullable(timestamp).map(Long::parseLong).map(Instant::ofEpochSecond);
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }
}
