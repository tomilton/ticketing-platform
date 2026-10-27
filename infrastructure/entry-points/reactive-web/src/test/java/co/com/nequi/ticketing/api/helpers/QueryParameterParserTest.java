package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryParameterParserTest {

    private static final String FIRST_ID = "3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f";
    private static final String SECOND_ID = "7b2d4c6e-8f0a-4b1c-9d2e-3f4a5b6c7d8e";
    private static final Integer DEFAULT_LIMIT = 20;
    private static final Integer MAX_LIMIT = 100;
    private static final Integer MAX_IDS = 3;

    @Test
    void shouldParseEventIdWhenValueIsUuid() {
        // When / Then
        assertThat(QueryParameterParser.parseEventId(FIRST_ID)).isEqualTo(new EventId(UUID.fromString(FIRST_ID)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-es-uuid", "1-1-1-1-1", ""})
    void shouldFailNamingEventIdWhenValueIsNotUuid(String value) {
        // When / Then
        assertThatThrownBy(() -> QueryParameterParser.parseEventId(value))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void shouldUseDefaultLimitWhenLimitIsMissing() {
        // When / Then
        assertThat(QueryParameterParser.parseLimit(null, DEFAULT_LIMIT, MAX_LIMIT)).isEqualTo(DEFAULT_LIMIT);
    }

    @Test
    void shouldParseLimitWhenValueIsWithinRange() {
        // When / Then
        assertThat(QueryParameterParser.parseLimit("100", DEFAULT_LIMIT, MAX_LIMIT)).isEqualTo(MAX_LIMIT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "abc", "-1", ""})
    void shouldFailNamingLimitWhenValueIsInvalid(String value) {
        // When / Then
        assertThatThrownBy(() -> QueryParameterParser.parseLimit(value, DEFAULT_LIMIT, MAX_LIMIT))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void shouldKeepRequestOrderAndRepeatedIdsWhenIdsAreValid() {
        // When / Then
        assertThat(QueryParameterParser.parseEventIds(FIRST_ID + "," + FIRST_ID + "," + SECOND_ID, MAX_IDS))
                .containsExactly(new EventId(UUID.fromString(FIRST_ID)), new EventId(UUID.fromString(FIRST_ID)),
                        new EventId(UUID.fromString(SECOND_ID)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", FIRST_ID + ",no-es-uuid", FIRST_ID + ",," + SECOND_ID})
    void shouldFailNamingIdsWhenIdsAreMissingOrInvalid(String value) {
        // When / Then
        assertThatThrownBy(() -> QueryParameterParser.parseEventIds(value, MAX_IDS))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ids");
    }

    @Test
    void shouldFailNamingIdsWhenThereAreMoreIdsThanTheMaximum() {
        // Given
        String ids = String.join(",", Collections.nCopies(MAX_IDS + 1, FIRST_ID));

        // When / Then
        assertThatThrownBy(() -> QueryParameterParser.parseEventIds(ids, MAX_IDS))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ids");
    }
}
