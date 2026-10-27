package co.com.nequi.ticketing.api.event.mapper;

import co.com.nequi.ticketing.api.event.dto.request.CreateEventRequestDto;
import co.com.nequi.ticketing.api.event.dto.response.EventPageResponseDto;
import co.com.nequi.ticketing.api.event.dto.response.EventResponseDto;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.EventPage;
import co.com.nequi.ticketing.model.event.command.CreateEventCommand;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps the event DTOs to the domain and back.
 */
@Mapper(componentModel = "spring")
public interface EventMapper {

    @Mapping(target = "venueName", source = "venue.name")
    @Mapping(target = "city", source = "venue.city")
    CreateEventCommand toCommand(CreateEventRequestDto request);

    @Mapping(target = "eventId", source = "eventId.value")
    @Mapping(target = "capacity", source = "capacity.value")
    EventResponseDto toResponse(Event event);

    EventPageResponseDto toResponse(EventPage page);
}
