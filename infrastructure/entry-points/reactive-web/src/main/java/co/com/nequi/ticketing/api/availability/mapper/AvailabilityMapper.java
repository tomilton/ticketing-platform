package co.com.nequi.ticketing.api.availability.mapper;

import co.com.nequi.ticketing.api.availability.dto.response.AvailabilityResponseDto;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps the availability of the domain to its DTO.
 */
@Mapper(componentModel = "spring")
public interface AvailabilityMapper {

    @Mapping(target = "eventId", source = "eventId.value")
    AvailabilityResponseDto toResponse(EventInventory inventory);
}
