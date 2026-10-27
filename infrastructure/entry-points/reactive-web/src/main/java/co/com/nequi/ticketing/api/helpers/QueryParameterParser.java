package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parses path and query parameters. Identifiers are validated with {@link UUID#fromString} and
 * no regular expression; every error names the parameter.
 */
@UtilityClass
public class QueryParameterParser {

    public static final String EVENT_ID = "eventId";
    public static final String LIMIT = "limit";
    public static final String IDS = "ids";
    public static final String CURSOR = "cursor";

    private static final String ID_SEPARATOR = ",";
    private static final int KEEP_EMPTY_PARTS = -1;
    private static final int MIN_LIMIT = 1;

    public static EventId parseEventId(String value) {
        return toEventId(value).orElseThrow(() -> new ValidationException(EVENT_ID, "must be a UUID"));
    }

    public static Integer parseLimit(String value, Integer defaultLimit, Integer maxLimit) {
        return Optional.ofNullable(value)
                .map(text -> toLimit(text, maxLimit))
                .orElse(defaultLimit);
    }

    public static List<EventId> parseEventIds(String value, Integer maxIds) {
        String text = Optional.ofNullable(value).filter(ids -> !ids.isBlank())
                .orElseThrow(() -> new ValidationException(IDS, "is required"));
        List<String> parts = Arrays.asList(text.split(ID_SEPARATOR, KEEP_EMPTY_PARTS));
        if (parts.size() > maxIds) {
            throw new ValidationException(IDS, "must have at most " + maxIds + " values");
        }
        return parts.stream()
                .map(part -> toEventId(part.trim())
                        .orElseThrow(() -> new ValidationException(IDS, "must contain only UUIDs")))
                .toList();
    }

    private static Integer toLimit(String text, Integer maxLimit) {
        return toInteger(text)
                .filter(number -> number >= MIN_LIMIT && number <= maxLimit)
                .orElseThrow(() -> new ValidationException(LIMIT,
                        "must be an integer between " + MIN_LIMIT + " and " + maxLimit));
    }

    private static Optional<Integer> toInteger(String text) {
        try {
            return Optional.of(Integer.valueOf(text));
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    private static Optional<EventId> toEventId(String value) {
        try {
            return Optional.ofNullable(value)
                    .map(UUID::fromString)
                    .filter(uuid -> uuid.toString().equalsIgnoreCase(value))
                    .map(EventId::new);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
