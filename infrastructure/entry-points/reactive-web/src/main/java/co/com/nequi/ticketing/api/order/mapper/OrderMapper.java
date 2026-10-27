package co.com.nequi.ticketing.api.order.mapper;

import co.com.nequi.ticketing.api.order.dto.request.PlaceOrderRequestDto;
import co.com.nequi.ticketing.api.order.dto.response.OrderResponseDto;
import co.com.nequi.ticketing.api.order.dto.response.PlaceOrderResponseDto;
import co.com.nequi.ticketing.api.order.dto.response.TicketResponseDto;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.OrderView;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.Ticket;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps the order DTOs to the domain and back.
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    default PlaceOrderCommand toCommand(CustomerId customerId, IdempotencyKey key, PlaceOrderRequestDto request) {
        return new PlaceOrderCommand(customerId, key, new EventId(request.eventId()), new Quantity(request.quantity()));
    }

    @Mapping(target = "orderId", source = "orderId.value")
    PlaceOrderResponseDto toResponse(PlacementOutcome.Accepted accepted);

    @Mapping(target = "orderId", source = "order.orderId.value")
    @Mapping(target = "eventId", source = "order.eventId.value")
    @Mapping(target = "quantity", source = "order.quantity")
    @Mapping(target = "status", source = "effectiveStatus")
    @Mapping(target = "expiresAt", source = "order.expiresAt")
    OrderResponseDto toResponse(OrderView view);

    @Mapping(target = "ticketId", source = "ticketId.value")
    TicketResponseDto toResponse(Ticket ticket);
}
