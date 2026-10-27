package co.com.nequi.ticketing.model.idempotency;

import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.EventId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Hash of the content of a purchase request. Two requests with the same key must have the same
 * fingerprint to be treated as a repetition.
 *
 * @param value the SHA-256 of the canonical content, as {@value #LENGTH} lowercase hexadecimal characters
 */
public record RequestFingerprint(String value) {

    public static final int LENGTH = 64;
    private static final String ALGORITHM = "SHA-256";
    private static final String SEPARATOR = "|";

    public RequestFingerprint {
        Objects.requireNonNull(value, "Fingerprint value must not be null");
        if (value.length() != LENGTH || !value.chars().allMatch(RequestFingerprint::isLowercaseHexDigit)) {
            throw new IllegalArgumentException("Fingerprint must have " + LENGTH + " hexadecimal characters");
        }
    }

    /**
     * Calculates the fingerprint of a purchase request from its canonical form {@code eventId|quantity}.
     *
     * @param eventId  the event of the request
     * @param quantity the number of tickets of the request
     * @return the fingerprint of the request
     */
    public static RequestFingerprint of(EventId eventId, Quantity quantity) {
        String canonical = eventId.value() + SEPARATOR + quantity.value();
        return new RequestFingerprint(HexFormat.of().formatHex(sha256().digest(canonical.getBytes(StandardCharsets.UTF_8))));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The platform does not provide " + ALGORITHM, exception);
        }
    }

    private static boolean isLowercaseHexDigit(int character) {
        return HexFormat.isHexDigit(character) && !Character.isUpperCase(character);
    }
}
