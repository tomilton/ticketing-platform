package co.com.nequi.ticketing.dynamodb.pagination;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.VALIDATION_ERROR;

/**
 * Turns the last evaluated key of the events index into an opaque cursor and back. The cursor is
 * the key as JSON encoded in Base64URL; a cursor that cannot be decoded is a validation error.
 */
@Component
@RequiredArgsConstructor
public class EventPageCursorCodec {

    private static final String EVENT_ID = "eventId";
    private static final String STATUS = "status";
    private static final String DATE = "date";

    private final ObjectMapper objectMapper;

    public Optional<String> encode(Map<String, AttributeValue> lastEvaluatedKey) {
        return Optional.ofNullable(lastEvaluatedKey)
                .filter(key -> !key.isEmpty())
                .map(key -> new CursorKey(key.get(EVENT_ID).s(), key.get(STATUS).s(), Long.valueOf(key.get(DATE).n())))
                .map(objectMapper::writeValueAsBytes)
                .map(Base64.getUrlEncoder().withoutPadding()::encodeToString);
    }

    public Optional<Map<String, AttributeValue>> decode(String cursor) {
        return Optional.ofNullable(cursor).map(this::toExclusiveStartKey);
    }

    private Map<String, AttributeValue> toExclusiveStartKey(String cursor) {
        try {
            CursorKey key = objectMapper.readValue(Base64.getUrlDecoder().decode(cursor), CursorKey.class);
            return Map.of(
                    EVENT_ID, AttributeValue.fromS(UUID.fromString(key.eventId()).toString()),
                    STATUS, AttributeValue.fromS(Objects.requireNonNull(key.status())),
                    DATE, AttributeValue.fromN(Objects.requireNonNull(key.date()).toString()));
        } catch (RuntimeException error) {
            throw VALIDATION_ERROR.build(error);
        }
    }

    private record CursorKey(String eventId, String status, Long date) {
    }
}
