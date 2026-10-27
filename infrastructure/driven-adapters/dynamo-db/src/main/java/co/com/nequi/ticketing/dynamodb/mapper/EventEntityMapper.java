package co.com.nequi.ticketing.dynamodb.mapper;

import co.com.nequi.ticketing.dynamodb.entity.EventEntity;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Maps {@link Event} to {@link EventEntity} and back. The venue is flattened into two attributes.
 */
@Mapper(componentModel = "spring")
public interface EventEntityMapper extends DomainValueConversions {

    @Mapping(target = "venueName", source = "venue.name")
    @Mapping(target = "city", source = "venue.city")
    EventEntity toEntity(Event event);

    @Mapping(target = "venue", source = "entity")
    Event buildEvent(EventEntity entity);

    /**
     * Rebuilds the event of an item. An item that breaks a domain rule is a data error.
     *
     * @param entity the stored item
     * @return the event
     */
    default Event toDomain(EventEntity entity) {
        try {
            return buildEvent(entity);
        } catch (RuntimeException error) {
            throw DYNAMODB_ERROR.build(error);
        }
    }

    default Venue toVenue(EventEntity entity) {
        return new Venue(entity.getVenueName(), entity.getCity());
    }
}
