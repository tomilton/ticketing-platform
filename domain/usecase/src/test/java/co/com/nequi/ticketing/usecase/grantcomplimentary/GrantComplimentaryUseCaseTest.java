package co.com.nequi.ticketing.usecase.grantcomplimentary;

import co.com.nequi.ticketing.model.config.ComplimentarySettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.command.GrantComplimentaryCommand;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrantComplimentaryUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Integer SHARD_COUNT = 3;
    private static final Event EVENT = Event.create(EventId.newId(), "Rock al Parque",
            new Venue("Parque Simon Bolivar", "Bogota"), Instant.parse("2026-12-01T20:00:00Z"), new Capacity(50),
            SHARD_COUNT);
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());

    @Mock
    private EventRepository eventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Test
    void shouldGrantComplimentaryOrderWhenEventIsOnSale() {
        // Given
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        when(orderRepository.grantComplimentary(captor.capture(), eq(EVENT), eq(NOW)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .assertNext(order -> assertThat(order).isSameAs(captor.getValue()))
                .verifyComplete();
        Order granted = captor.getValue();
        assertThat(granted.getStatus()).isEqualTo(OrderStatus.COMPLIMENTARY);
        assertThat(granted.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(granted.getEventId()).isEqualTo(EVENT.getEventId());
        assertThat(granted.getQuantity()).isEqualTo(3);
        assertThat(granted.getShardId()).isBetween(0, SHARD_COUNT - 1);
    }

    @Test
    void shouldFailWithLimitExceededWhenQuantityIsAboveConfiguredMaximum() {
        // When / Then
        StepVerifier.create(useCase(10).grant(command(11)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKET_LIMIT_EXCEEDED))
                .verify();
        verify(eventRepository, never()).findById(any());
    }

    @Test
    void shouldFailWithEventNotFoundWhenEventDoesNotExist() {
        // Given
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void shouldFailWithEventNotActiveWhenEventIsInactive() {
        // Given
        when(eventRepository.findById(EVENT.getEventId()))
                .thenReturn(Mono.just(EVENT.toBuilder().status(EventStatus.CANCELLED).build()));

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_ACTIVE))
                .verify();
        verify(orderRepository, never()).grantComplimentary(any(), any(), any());
    }

    @Test
    void shouldFailWithEventNotActiveWhenEventAlreadyStarted() {
        // Given
        when(eventRepository.findById(EVENT.getEventId()))
                .thenReturn(Mono.just(EVENT.toBuilder().date(NOW.minusSeconds(1)).build()));

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_ACTIVE))
                .verify();
    }

    @Test
    void shouldPropagateSoldOutWhenRepositoryHasNoTickets() {
        // Given
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        when(orderRepository.grantComplimentary(any(Order.class), eq(EVENT), eq(NOW)))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorMessage.TICKETS_SOLD_OUT)));

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKETS_SOLD_OUT))
                .verify();
    }

    private GrantComplimentaryUseCase useCase(Integer maxPerGrant) {
        return new GrantComplimentaryUseCase(eventRepository, orderRepository, () -> NOW,
                new ComplimentarySettings(maxPerGrant));
    }

    private static GrantComplimentaryCommand command(Integer quantity) {
        return new GrantComplimentaryCommand(EVENT.getEventId(), CUSTOMER_ID, new ComplimentaryQuantity(quantity),
                new CustomerId(UUID.randomUUID()), new IdempotencyKey(UUID.randomUUID()));
    }

    private static void assertBusinessError(Throwable error, BusinessErrorMessage expected) {
        assertThat(error).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                .isEqualTo(expected);
    }
}
