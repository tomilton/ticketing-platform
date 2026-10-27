package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversions between the value objects of the domain and the attribute types of the entities,
 * shared by the entity mappers.
 */
public interface DomainValueConversions {

    default String toText(EventId eventId) {
        return eventId.value().toString();
    }

    default EventId toEventId(String eventId) {
        return new EventId(UUID.fromString(eventId));
    }

    default Long toEpochMilli(Instant date) {
        return date.toEpochMilli();
    }

    default Instant toInstant(Long epochMilli) {
        return Instant.ofEpochMilli(epochMilli);
    }

    default Integer toValue(Capacity capacity) {
        return capacity.value();
    }

    default Capacity toCapacity(Integer capacity) {
        return new Capacity(capacity);
    }
}
