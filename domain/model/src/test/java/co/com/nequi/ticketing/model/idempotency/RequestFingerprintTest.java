package co.com.nequi.ticketing.model.idempotency;

import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestFingerprintTest {

    private static final EventId EVENT_ID = new EventId(UUID.fromString("3f2c1a9e-8b7d-4e6f-9a1b-2c3d4e5f6a7b"));
    private static final String KNOWN_HASH = "2ea937d8c6052b601d25044c854d34e6bd60861e7cc501c7ffd4b362db6880ea";

    @Test
    void shouldCalculateSha256OfCanonicalContentWhenOfIsCalled() {
        // When / Then
        assertThat(RequestFingerprint.of(EVENT_ID, new Quantity(2)).value()).isEqualTo(KNOWN_HASH);
    }

    @Test
    void shouldReturnSameFingerprintWhenContentIsTheSame() {
        // When / Then
        assertThat(RequestFingerprint.of(EVENT_ID, new Quantity(3)))
                .isEqualTo(RequestFingerprint.of(EVENT_ID, new Quantity(3)));
    }

    @Test
    void shouldReturnDifferentFingerprintWhenQuantityIsDifferent() {
        // When / Then
        assertThat(RequestFingerprint.of(EVENT_ID, new Quantity(3)))
                .isNotEqualTo(RequestFingerprint.of(EVENT_ID, new Quantity(4)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc",
            "2EA937D8C6052B601D25044C854D34E6BD60861E7CC501C7FFD4B362DB6880EA",
            "zea937d8c6052b601d25044c854d34e6bd60861e7cc501c7ffd4b362db6880ea",
            "2ea937d8c6052b601d25044c854d34e6bd60861e7cc501c7ffd4b362db6880ea0"})
    void shouldThrowIllegalArgumentExceptionWhenValueIsNotSixtyFourHexCharacters(String value) {
        // When / Then
        assertThatThrownBy(() -> new RequestFingerprint(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> new RequestFingerprint(null)).isInstanceOf(NullPointerException.class);
    }
}
