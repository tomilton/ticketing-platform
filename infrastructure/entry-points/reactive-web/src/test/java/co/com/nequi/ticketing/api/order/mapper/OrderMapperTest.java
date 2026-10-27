package co.com.nequi.ticketing.api.order.mapper;

import co.com.nequi.ticketing.api.order.dto.request.PlaceOrderRequestDto;
import co.com.nequi.ticketing.api.order.dto.response.OrderResponseDto;
import co.com.nequi.ticketing.api.order.dto.response.PlaceOrderResponseDto;
import co.com.nequi.ticketing.api.order.dto.response.TicketResponseDto;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderView;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.success.SuccessMessage;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());

    private final OrderMapper mapper = Mappers.getMapper(OrderMapper.class);

    @Test
    void shouldBuildCommandWhenRequestIsMapped() {
        // Given
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        UUID eventId = UUID.randomUUID();

        // When / Then
        assertThat(mapper.toCommand(CUSTOMER_ID, key, new PlaceOrderRequestDto(eventId, 2)))
                .isEqualTo(new PlaceOrderCommand(CUSTOMER_ID, key, new EventId(eventId), new Quantity(2)));
    }

    @Test
    void shouldExposeIdentifierStatusAndExpirationWhenAcceptedOrderIsMapped() {
        // Given
        OrderId orderId = OrderId.newId();

        // When / Then
        assertThat(mapper.toResponse(new PlacementOutcome.Accepted(orderId, OrderStatus.RESERVED, NOW)))
                .isEqualTo(new PlaceOrderResponseDto(orderId.value(), "RESERVED", NOW));
    }

    @Test
    void shouldExposeEffectiveStatusAndTicketsWhenViewIsMapped() {
        // Given
        Order order = Order.place(OrderId.newId(), CUSTOMER_ID, EventId.newId(), new Quantity(2), 0, NOW,
                Duration.ofMinutes(10));
        Instant expired = order.getExpiresAt();
        OrderView view = new OrderView(order, order.effectiveStatus(expired), order.ticketsAt(expired));

        // When
        OrderResponseDto response = mapper.toResponse(view);

        // Then
        assertThat(response).usingRecursiveComparison().isEqualTo(new OrderResponseDto(order.getOrderId().value(),
                order.getEventId().value(), 2, "EXPIRED", order.getExpiresAt(), order.getTickets().stream()
                .map(ticket -> new TicketResponseDto(ticket.value(), "AVAILABLE")).toList()));
    }

    @Test
    void shouldWrapPayloadWithSuccessMetaWhenEnvelopeIsBuilt() {
        // When / Then
        assertThat(new OrderMapManagement().buildResponse("payload", SuccessMessage.ACCEPTED).meta().code())
                .isEqualTo("TKS0003");
    }
}
