package co.com.nequi.ticketing.api.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-only-webhook-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-27T15:00:00Z");
    private static final String TIMESTAMP = String.valueOf(NOW.getEpochSecond());
    private static final byte[] BODY = "{\"eventId\":\"6f1c1c8e-2b55-4a0e-9a34-6c1f1c2f0b11\"}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(new WebhookProperties(SECRET, Duration.ofMinutes(5)));

    @Test
    void shouldAcceptNoticeInAnyCaseWhenSignatureAndTimestampAreValid() {
        // When / Then
        assertThat(verifier.isAuthentic(TIMESTAMP, sign(SECRET, TIMESTAMP, BODY), BODY, NOW)).isTrue();
        assertThat(verifier.isAuthentic(TIMESTAMP, sign(SECRET, TIMESTAMP, BODY).toUpperCase(Locale.ROOT), BODY, NOW))
                .isTrue();
    }

    @Test
    void shouldRejectNoticeWhenSignedWithAnotherSecret() {
        // When / Then
        assertThat(verifier.isAuthentic(TIMESTAMP, sign("another-secret", TIMESTAMP, BODY), BODY, NOW)).isFalse();
    }

    @Test
    void shouldRejectNoticeWhenBodyWasAltered() {
        // Given
        byte[] altered = "{\"eventId\":\"00000000-0000-4000-8000-000000000000\"}".getBytes(StandardCharsets.UTF_8);

        // When / Then
        assertThat(verifier.isAuthentic(TIMESTAMP, sign(SECRET, TIMESTAMP, BODY), altered, NOW)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-hex", "abc"})
    void shouldRejectNoticeWhenSignatureIsMissingOrMalformed(String signature) {
        // When / Then
        assertThat(verifier.isAuthentic(TIMESTAMP, signature, BODY, NOW)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301})
    void shouldRejectNoticeWhenTimestampIsOutsideTolerance(Long offsetSeconds) {
        // Given
        String timestamp = String.valueOf(NOW.getEpochSecond() + offsetSeconds);

        // When / Then
        assertThat(verifier.isAuthentic(timestamp, sign(SECRET, timestamp, BODY), BODY, NOW)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"yesterday", "1.5"})
    void shouldRejectNoticeWhenTimestampIsNotEpochSeconds(String timestamp) {
        // When / Then
        assertThat(verifier.isAuthentic(timestamp, sign(SECRET, TIMESTAMP, BODY), BODY, NOW)).isFalse();
    }

    @Test
    void shouldHideSecretWhenPropertiesArePrinted() {
        // When / Then
        assertThat(new WebhookProperties(SECRET, Duration.ofMinutes(5)).toString())
                .doesNotContain(SECRET)
                .contains("PT5M");
    }

    private static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
