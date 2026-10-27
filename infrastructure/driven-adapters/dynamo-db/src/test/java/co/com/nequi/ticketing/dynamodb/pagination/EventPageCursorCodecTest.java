package co.com.nequi.ticketing.dynamodb.pagination;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPageCursorCodecTest {

    private static final Map<String, AttributeValue> LAST_KEY = Map.of(
            "eventId", AttributeValue.fromS("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f"),
            "status", AttributeValue.fromS("ACTIVE"),
            "date", AttributeValue.fromN("1796155200123"));

    private final EventPageCursorCodec codec = new EventPageCursorCodec(JsonMapper.builder().build());

    @Test
    void shouldDecodeSameKeyWhenCursorWasEncoded() {
        // Given
        String cursor = codec.encode(LAST_KEY).orElseThrow();

        // When / Then
        assertThat(codec.decode(cursor)).contains(LAST_KEY);
    }

    @Test
    void shouldReturnNoCursorWhenLastKeyIsEmpty() {
        // When / Then
        assertThat(codec.encode(Map.of())).isEmpty();
    }

    @Test
    void shouldReturnNoKeyWhenCursorIsNull() {
        // When / Then
        assertThat(codec.decode(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "bm90LWpzb24", "eyJldmVudElkIjoieCIsInN0YXR1cyI6IkFDVElWRSIsImRhdGUiOjF9"})
    void shouldFailWithValidationErrorWhenCursorIsInvalid(String cursor) {
        // When / Then
        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.VALIDATION_ERROR);
    }

    @Test
    void shouldFailWithValidationErrorWhenCursorMissesAField() {
        // Given
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"eventId\":\"3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f\"}".getBytes(StandardCharsets.UTF_8));

        // When / Then
        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.VALIDATION_ERROR);
    }
}
