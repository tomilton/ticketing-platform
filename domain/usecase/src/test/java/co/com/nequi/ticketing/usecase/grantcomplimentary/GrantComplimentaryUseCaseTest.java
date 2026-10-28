package co.com.nequi.ticketing.usecase.grantcomplimentary;

import co.com.nequi.ticketing.model.config.ComplimentarySettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;
import co.com.nequi.ticketing.model.order.GrantContext;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
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
    private static final CustomerId ADMINISTRATOR_ID = new CustomerId(UUID.randomUUID());
    private static final IdempotencyScope SCOPE = IdempotencyScope.ofAdministrator(ADMINISTRATOR_ID);
    private static final IdempotencyKey KEY = new IdempotencyKey(UUID.randomUUID());
    private static final UUID LOCK_TOKEN = UUID.randomUUID();
    private static final GrantContext CONTEXT = new GrantContext(ADMINISTRATOR_ID, SCOPE, KEY, LOCK_TOKEN);

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Test
    void shouldGrantComplimentaryOrderClosingKeyWhenKeyIsGrantedAndEventIsOnSale() {
        // Given
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        claimGranted(3);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        when(orderRepository.grantComplimentary(captor.capture(), eq(EVENT), eq(CONTEXT), eq(NOW)))
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
        verify(idempotencyRepository, never()).complete(any(), any(), any(), any());
    }

    @Test
    void shouldStoreLimitExceededWhenQuantityIsAboveConfiguredMaximum() {
        // Given
        claimGranted(11);
        storeRejection(BusinessErrorMessage.TICKET_LIMIT_EXCEEDED);

        // When / Then
        StepVerifier.create(useCase(10).grant(command(11)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKET_LIMIT_EXCEEDED))
                .verify();
        verify(eventRepository, never()).findById(any());
    }

    @Test
    void shouldStoreEventNotFoundWhenEventDoesNotExist() {
        // Given
        claimGranted(3);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.empty());
        storeRejection(BusinessErrorMessage.EVENT_NOT_FOUND);

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void shouldStoreEventNotActiveWhenEventIsInactive() {
        // Given
        claimGranted(3);
        when(eventRepository.findById(EVENT.getEventId()))
                .thenReturn(Mono.just(EVENT.toBuilder().status(EventStatus.CANCELLED).build()));
        storeRejection(BusinessErrorMessage.EVENT_NOT_ACTIVE);

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_ACTIVE))
                .verify();
        verify(orderRepository, never()).grantComplimentary(any(), any(), any(), any());
    }

    @Test
    void shouldStoreEventNotActiveWhenEventAlreadyStarted() {
        // Given
        claimGranted(3);
        when(eventRepository.findById(EVENT.getEventId()))
                .thenReturn(Mono.just(EVENT.toBuilder().date(NOW.minusSeconds(1)).build()));
        storeRejection(BusinessErrorMessage.EVENT_NOT_ACTIVE);

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_ACTIVE))
                .verify();
    }

    @Test
    void shouldStoreSoldOutWhenRepositoryHasNoTickets() {
        // Given
        claimGranted(3);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        when(orderRepository.grantComplimentary(any(Order.class), eq(EVENT), eq(CONTEXT), eq(NOW)))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorMessage.TICKETS_SOLD_OUT)));
        storeRejection(BusinessErrorMessage.TICKETS_SOLD_OUT);

        // When / Then
        StepVerifier.create(useCase(50).grant(command(3)))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKETS_SOLD_OUT))
                .verify();
    }

    private void claimGranted(Integer quantity) {
        when(idempotencyRepository.claim(SCOPE, KEY, fingerprint(quantity), NOW))
                .thenReturn(Mono.just(new IdempotencyClaim.Granted(LOCK_TOKEN)));
    }

    private void storeRejection(BusinessErrorMessage rejection) {
        when(idempotencyRepository.complete(SCOPE, KEY, LOCK_TOKEN, new PlacementOutcome.Rejected(rejection)))
                .thenReturn(Mono.empty());
    }

    private GrantComplimentaryUseCase useCase(Integer maxPerGrant) {
        return new GrantComplimentaryUseCase(idempotencyRepository, eventRepository, orderRepository, () -> NOW,
                new ComplimentarySettings(maxPerGrant));
    }

    private static RequestFingerprint fingerprint(Integer quantity) {
        return RequestFingerprint.ofGrant(EVENT.getEventId(), CUSTOMER_ID, new ComplimentaryQuantity(quantity));
    }

    private static GrantComplimentaryCommand command(Integer quantity) {
        return new GrantComplimentaryCommand(EVENT.getEventId(), CUSTOMER_ID, new ComplimentaryQuantity(quantity),
                ADMINISTRATOR_ID, KEY);
    }

    private static void assertBusinessError(Throwable error, BusinessErrorMessage expected) {
        assertThat(error).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                .isEqualTo(expected);
    }
}
