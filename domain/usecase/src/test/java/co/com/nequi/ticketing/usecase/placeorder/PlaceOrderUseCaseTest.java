package co.com.nequi.ticketing.usecase.placeorder;

import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyRecord;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final CustomerId CUSTOMER_ID = new CustomerId(UUID.randomUUID());
    private static final IdempotencyScope SCOPE = IdempotencyScope.ofCustomer(CUSTOMER_ID);
    private static final IdempotencyKey KEY = new IdempotencyKey(UUID.randomUUID());
    private static final UUID LOCK_TOKEN = UUID.randomUUID();
    private static final Integer SHARD_COUNT = 3;
    private static final Event EVENT = Event.create(EventId.newId(), "Rock al Parque",
            new Venue("Parque Simon Bolivar", "Bogota"), Instant.parse("2026-12-01T20:00:00Z"), new Capacity(50),
            SHARD_COUNT);
    private static final PlaceOrderCommand COMMAND = new PlaceOrderCommand(CUSTOMER_ID, KEY, EVENT.getEventId(),
            new Quantity(2));
    private static final RequestFingerprint FINGERPRINT = RequestFingerprint.of(EVENT.getEventId(), new Quantity(2));

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private ClockProvider clockProvider;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    @Captor
    private ArgumentCaptor<OrderPlacedMessage> messageCaptor;

    private PlaceOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PlaceOrderUseCase(idempotencyRepository, eventRepository, orderRepository, orderEventPublisher,
                clockProvider, new OrderSettings(4, 6, TTL, Duration.ofSeconds(5), 4));
        when(clockProvider.now()).thenReturn(NOW);
    }

    @Test
    void shouldReserveAndPublishOrderWhenKeyIsGrantedAndEventIsOnSale() {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        givenReservationSucceeds();
        when(orderEventPublisher.publishOrderPlaced(messageCaptor.capture())).thenReturn(Mono.empty());
        when(orderRepository.markPublished(any(OrderId.class), eq(NOW))).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(accepted -> {
                    Order order = orderCaptor.getValue();
                    assertThat(accepted).isEqualTo(PlacementOutcome.Accepted.of(order));
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
                    assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);
                    assertThat(order.getExpiresAt()).isEqualTo(NOW.plus(TTL));
                    assertThat(order.getShardId()).isBetween(0, SHARD_COUNT - 1);
                    assertThat(messageCaptor.getValue().orderId()).isEqualTo(order.getOrderId());
                    assertThat(messageCaptor.getValue().quantity()).isEqualTo(new Quantity(2));
                    verify(orderRepository).markPublished(order.getOrderId(), NOW);
                })
                .verifyComplete();
        verify(orderRepository).reserve(any(Order.class), eq(EVENT),
                eq(new ReservationContext(CUSTOMER_ID, KEY, LOCK_TOKEN, 6)), eq(NOW));
        verify(idempotencyRepository, never()).complete(any(), any(), any(), any());
    }

    @Test
    void shouldStoreLimitExceededWhenQuantityIsAboveMaxPerOrder() {
        // Given
        PlaceOrderCommand command = new PlaceOrderCommand(CUSTOMER_ID, KEY, EVENT.getEventId(), new Quantity(5));
        when(idempotencyRepository.claim(SCOPE, KEY, RequestFingerprint.of(EVENT.getEventId(), new Quantity(5)),
                NOW)).thenReturn(Mono.just(new IdempotencyClaim.Granted(LOCK_TOKEN)));
        givenRejectionIsStored(BusinessErrorMessage.TICKET_LIMIT_EXCEEDED);

        // When / Then
        StepVerifier.create(useCase.place(command))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKET_LIMIT_EXCEEDED))
                .verify();
        verifyNoInteractions(eventRepository, orderRepository);
    }

    @Test
    void shouldStoreEventNotFoundWhenEventDoesNotExist() {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.empty());
        givenRejectionIsStored(BusinessErrorMessage.EVENT_NOT_FOUND);

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_FOUND))
                .verify();
        verifyNoInteractions(orderRepository);
    }

    @Test
    void shouldStoreEventNotActiveWhenEventIsCancelled() {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId()))
                .thenReturn(Mono.just(EVENT.toBuilder().status(EventStatus.CANCELLED).build()));
        givenRejectionIsStored(BusinessErrorMessage.EVENT_NOT_ACTIVE);

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_ACTIVE))
                .verify();
        verifyNoInteractions(orderRepository);
    }

    @Test
    void shouldStoreEventNotActiveWhenEventDateHasPassed() {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT.toBuilder().date(NOW).build()));
        givenRejectionIsStored(BusinessErrorMessage.EVENT_NOT_ACTIVE);

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_ACTIVE))
                .verify();
    }

    @ParameterizedTest
    @EnumSource(value = BusinessErrorMessage.class, names = {"TICKETS_SOLD_OUT", "TICKET_LIMIT_EXCEEDED",
            "EVENT_NOT_ACTIVE"})
    void shouldStoreRejectionWhenReservationIsRejected(BusinessErrorMessage rejection) {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        when(orderRepository.reserve(any(Order.class), eq(EVENT), any(ReservationContext.class), eq(NOW)))
                .thenReturn(Mono.error(rejection.build()));
        givenRejectionIsStored(rejection);

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, rejection))
                .verify();
        verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void shouldPropagateRequestInProgressWithoutClosingKeyWhenReservationLostTheLock() {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        when(orderRepository.reserve(any(Order.class), eq(EVENT), any(ReservationContext.class), eq(NOW)))
                .thenReturn(Mono.error(BusinessErrorMessage.REQUEST_IN_PROGRESS.build()));

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.REQUEST_IN_PROGRESS))
                .verify();
        verify(idempotencyRepository, never()).complete(any(), any(), any(), any());
        verify(idempotencyRepository, never()).release(any(), any(), any());
    }

    @Test
    void shouldReturnStoredOrderWithoutReservingWhenKeyIsCompletedWithAcceptedOrder() {
        // Given
        PlacementOutcome.Accepted stored = new PlacementOutcome.Accepted(OrderId.newId(), OrderStatus.RESERVED,
                NOW.plus(TTL));
        givenExistingKey(IdempotencyStatus.COMPLETED, FINGERPRINT, stored);

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectNext(stored)
                .verifyComplete();
        verifyNoInteractions(eventRepository, orderRepository, orderEventPublisher);
    }

    @Test
    void shouldFailWithStoredErrorWhenKeyIsCompletedWithRejection() {
        // Given
        givenExistingKey(IdempotencyStatus.COMPLETED, FINGERPRINT,
                new PlacementOutcome.Rejected(BusinessErrorMessage.TICKETS_SOLD_OUT));

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKETS_SOLD_OUT))
                .verify();
        verifyNoInteractions(eventRepository, orderRepository);
    }

    @ParameterizedTest
    @EnumSource(IdempotencyStatus.class)
    void shouldFailWithKeyReusedWhenFingerprintIsDifferent(IdempotencyStatus status) {
        // Given
        givenExistingKey(status, RequestFingerprint.of(EVENT.getEventId(), new Quantity(3)),
                new PlacementOutcome.Rejected(BusinessErrorMessage.TICKETS_SOLD_OUT));

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.IDEMPOTENCY_KEY_REUSED))
                .verify();
        verifyNoInteractions(eventRepository, orderRepository);
    }

    @Test
    void shouldFailWithRequestInProgressWhenKeyIsHeldByAnotherRequest() {
        // Given
        givenExistingKey(IdempotencyStatus.IN_PROGRESS, FINGERPRINT, null);

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.REQUEST_IN_PROGRESS))
                .verify();
        verifyNoInteractions(eventRepository, orderRepository);
    }

    @Test
    void shouldReleaseKeyAndPropagateErrorWhenReservationFailsTechnically() {
        // Given
        TimeoutException timeout = new TimeoutException("no answer");
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        when(orderRepository.reserve(any(Order.class), eq(EVENT), any(ReservationContext.class), eq(NOW)))
                .thenReturn(Mono.error(timeout));
        when(idempotencyRepository.release(SCOPE, KEY, LOCK_TOKEN)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .expectErrorMatches(error -> error == timeout)
                .verify();
        verify(idempotencyRepository, never()).complete(any(), any(), any(), any());
        verifyNoInteractions(orderEventPublisher);
    }

    @Test
    void shouldAcceptOrderWithoutMarkingItWhenPublicationFails() {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        givenReservationSucceeds();
        when(orderEventPublisher.publishOrderPlaced(any(OrderPlacedMessage.class)))
                .thenReturn(Mono.error(new IllegalStateException("queue down")));

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(accepted -> assertThat(accepted.orderId()).isEqualTo(orderCaptor.getValue().getOrderId()))
                .verifyComplete();
        verify(orderRepository, never()).markPublished(any(), any());
    }

    @Test
    void shouldAcceptOrderWhenMarkingItAsPublishedFails() {
        // Given
        givenGrantedKey();
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        givenReservationSucceeds();
        when(orderEventPublisher.publishOrderPlaced(any(OrderPlacedMessage.class))).thenReturn(Mono.empty());
        when(orderRepository.markPublished(any(OrderId.class), eq(NOW)))
                .thenReturn(Mono.error(new IllegalStateException("database down")));

        // When / Then
        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(accepted -> assertThat(accepted.status()).isEqualTo(OrderStatus.RESERVED))
                .verifyComplete();
    }

    private void givenGrantedKey() {
        when(idempotencyRepository.claim(SCOPE, KEY, FINGERPRINT, NOW))
                .thenReturn(Mono.just(new IdempotencyClaim.Granted(LOCK_TOKEN)));
    }

    private void givenExistingKey(IdempotencyStatus status, RequestFingerprint fingerprint,
                                  PlacementOutcome outcome) {
        IdempotencyRecord stored = new IdempotencyRecord(SCOPE, KEY, fingerprint, status, UUID.randomUUID(),
                NOW.plusSeconds(10), outcome);
        when(idempotencyRepository.claim(SCOPE, KEY, FINGERPRINT, NOW))
                .thenReturn(Mono.just(new IdempotencyClaim.Existing(stored)));
    }

    private void givenReservationSucceeds() {
        when(orderRepository.reserve(orderCaptor.capture(), eq(EVENT), any(ReservationContext.class), eq(NOW)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    private void givenRejectionIsStored(BusinessErrorMessage rejection) {
        when(idempotencyRepository.complete(SCOPE, KEY, LOCK_TOKEN, new PlacementOutcome.Rejected(rejection)))
                .thenReturn(Mono.empty());
    }

    private static void assertBusinessError(Throwable error, BusinessErrorMessage expected) {
        assertThat(error).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                .isEqualTo(expected);
    }
}
