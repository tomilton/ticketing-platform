package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.entity.EventEntity;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEntityMapperTest {

    private static final UUID EVENT_ID = UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f");
    private static final Instant DATE = Instant.parse("2026-12-01T20:00:00.123Z");

    private final EventEntityMapper mapper = Mappers.getMapper(EventEntityMapper.class);

    @Test
    void shouldFlattenVenueAndStoreDateInMillisecondsWhenEventIsMapped() {
        // Given
        Event event = Event.create(new EventId(EVENT_ID), "Rock al Parque",
                new Venue("Parque Simon Bolivar", "Bogota"), DATE, new Capacity(500), 3);

        // When
        EventEntity entity = mapper.toEntity(event);

        // Then
        assertThat(entity).usingRecursiveComparison().isEqualTo(entity(EVENT_ID.toString()));
    }

    @Test
    void shouldRebuildEventWhenEntityIsMapped() {
        // Given
        Event expected = Event.create(new EventId(EVENT_ID), "Rock al Parque",
                new Venue("Parque Simon Bolivar", "Bogota"), DATE, new Capacity(500), 3);

        // When
        Event event = mapper.toDomain(entity(EVENT_ID.toString()));

        // Then
        assertThat(event).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenEntityIsInvalid() {
        // Given
        EventEntity invalid = entity("not-a-uuid");

        // When / Then
        assertThatThrownBy(() -> mapper.toDomain(invalid))
                .isInstanceOf(TechnicalException.class)
                .extracting(error -> ((TechnicalException) error).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }

    private static EventEntity entity(String eventId) {
        EventEntity entity = new EventEntity();
        entity.setEventId(eventId);
        entity.setName("Rock al Parque");
        entity.setVenueName("Parque Simon Bolivar");
        entity.setCity("Bogota");
        entity.setDate(DATE.toEpochMilli());
        entity.setCapacity(500);
        entity.setStatus(EventStatus.ACTIVE);
        entity.setShardCount(3);
        entity.setVersion(1L);
        return entity;
    }
}
