package co.com.nequi.ticketing.usecase.processorder;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderTransition;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessOrderUseCaseTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant NOW = CREATED_AT.plusSeconds(30);
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Instant EXPIRES_AT = CREATED_AT.plus(TTL);
    private static final Event EVENT = Event.create(EventId.newId(), "Rock al Parque",
            new Venue("Parque Simon Bolivar", "Bogota"), Instant.parse("2026-12-01T20:00:00Z"), new Capacity(50), 1);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private ClockProvider clockProvider;

    @Captor
    private ArgumentCaptor<OrderTransition> transitionCaptor;

    private ProcessOrderUseCase useCase;

    private Order reserved;

    @BeforeEach
    void setUp() {
        useCase = new ProcessOrderUseCase(orderRepository, eventRepository, paymentGateway, clockProvider);
        reserved = Order.place(OrderId.newId(), new CustomerId(UUID.randomUUID()), EVENT.getEventId(),
                new Quantity(2), 0, CREATED_AT, TTL);
    }

    @Test
    void shouldFailWithOrderNotFoundWhenOrderDoesNotExist() {
        // Given
        when(orderRepository.findById(reserved.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.ORDER_NOT_FOUND))
                .verify();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SOLD", "CANCELLED", "EXPIRED"})
    void shouldIgnoreMessageWhenOrderIsFinal(OrderStatus status) {
        // Given
        givenStored(withStatus(status));
        when(clockProvider.now()).thenReturn(NOW);

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.IGNORED)
                .verifyComplete();
        verifyNoInteractions(paymentGateway, eventRepository);
        verify(orderRepository, never()).apply(any(), any());
    }

    @Test
    void shouldSellTicketsWhenReservationIsValidEventIsOnSaleAndPaymentIsApproved() {
        // Given
        givenStored(reserved);
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        givenApplySucceeds(InventoryMovement.NONE);
        givenApplySucceeds(InventoryMovement.SELL);
        when(paymentGateway.charge(reserved.getOrderId())).thenReturn(Mono.just(PaymentResult.APPROVED));

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        assertThat(transitionCaptor.getAllValues()).extracting(transition -> transition.change().to())
                .containsExactly(OrderStatus.PENDING_CONFIRMATION, OrderStatus.SOLD);
        assertThat(transitionCaptor.getAllValues()).extracting(transition -> transition.change().reason())
                .containsExactly(StatusChangeReason.PAYMENT_REQUESTED, StatusChangeReason.PAYMENT_APPROVED);
    }

    @Test
    void shouldCancelAndReleaseWithoutReadingEventWhenPendingPaymentIsDeclined() {
        // Given
        givenStored(withStatus(OrderStatus.PENDING_CONFIRMATION));
        when(clockProvider.now()).thenReturn(NOW);
        when(paymentGateway.charge(reserved.getOrderId())).thenReturn(Mono.just(PaymentResult.DECLINED));
        givenApplySucceeds(InventoryMovement.RELEASE);

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.CANCELLED)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().reason()).isEqualTo(StatusChangeReason.PAYMENT_DECLINED);
        verifyNoInteractions(eventRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "STARTED", "STARTING_NOW", "MISSING"})
    void shouldCancelAndReleaseWhenEventIsNotOnSale(String eventState) {
        // Given
        givenStored(reserved);
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(switch (eventState) {
            case "CANCELLED" -> Mono.just(EVENT.toBuilder().status(EventStatus.CANCELLED).build());
            case "STARTED" -> Mono.just(EVENT.toBuilder().date(NOW.minusSeconds(1)).build());
            case "STARTING_NOW" -> Mono.just(EVENT.toBuilder().date(NOW).build());
            default -> Mono.empty();
        });
        givenApplySucceeds(InventoryMovement.RELEASE);

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.CANCELLED)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().to()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(transitionCaptor.getValue().change().reason()).isEqualTo(StatusChangeReason.EVENT_NOT_ACTIVE);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldExpireWithoutChargingOrRefundingWhenReservedOrderExpired() {
        // Given
        givenStored(reserved);
        when(clockProvider.now()).thenReturn(EXPIRES_AT.plusSeconds(1));
        givenApplySucceeds(InventoryMovement.RELEASE);

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.EXPIRED)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().reason()).isEqualTo(StatusChangeReason.RESERVATION_EXPIRED);
        verifyNoInteractions(paymentGateway, eventRepository);
    }

    @Test
    void shouldExpireAndRefundWithoutChargingWhenPendingOrderReachesExpiration() {
        // Given
        givenStored(withStatus(OrderStatus.PENDING_CONFIRMATION));
        when(clockProvider.now()).thenReturn(EXPIRES_AT);
        givenApplySucceeds(InventoryMovement.RELEASE);
        when(paymentGateway.refund(reserved.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.EXPIRED)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().reason()).isEqualTo(StatusChangeReason.PAYMENT_AFTER_EXPIRY);
        verify(paymentGateway, never()).charge(any());
    }

    @Test
    void shouldChargeOnceWithSameOrderWhenPendingOrderIsResumed() {
        // Given
        Order pending = withStatus(OrderStatus.PENDING_CONFIRMATION);
        givenStored(pending);
        when(clockProvider.now()).thenReturn(NOW);
        when(paymentGateway.charge(pending.getOrderId())).thenReturn(Mono.just(PaymentResult.APPROVED));
        givenApplySucceeds(InventoryMovement.SELL);

        // When / Then
        StepVerifier.create(useCase.process(pending.getOrderId()))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        verify(paymentGateway, times(1)).charge(pending.getOrderId());
        verifyNoInteractions(eventRepository);
    }

    @Test
    void shouldExpireAndRefundWhenPaymentIsApprovedAfterExpiration() {
        // Given
        givenStored(withStatus(OrderStatus.PENDING_CONFIRMATION));
        when(clockProvider.now()).thenReturn(NOW, EXPIRES_AT);
        when(paymentGateway.charge(reserved.getOrderId())).thenReturn(Mono.just(PaymentResult.APPROVED));
        givenApplySucceeds(InventoryMovement.RELEASE);
        when(paymentGateway.refund(reserved.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.EXPIRED)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().reason()).isEqualTo(StatusChangeReason.PAYMENT_AFTER_EXPIRY);
    }

    @Test
    void shouldExpireAndRefundWhenSaleIsRejectedAndRereadOrderExpired() {
        // Given
        Order pending = withStatus(OrderStatus.PENDING_CONFIRMATION);
        givenStored(pending, pending);
        when(clockProvider.now()).thenReturn(NOW, NOW, EXPIRES_AT);
        when(paymentGateway.charge(pending.getOrderId())).thenReturn(Mono.just(PaymentResult.APPROVED));
        givenApplyConflicts(InventoryMovement.SELL);
        givenApplySucceeds(InventoryMovement.RELEASE);
        when(paymentGateway.refund(pending.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.process(pending.getOrderId()))
                .expectNext(ProcessingOutcome.EXPIRED)
                .verifyComplete();
        verify(paymentGateway).refund(pending.getOrderId());
    }

    @Test
    void shouldIgnoreWithoutRefundWhenAnotherCopyAlreadySoldTheOrder() {
        // Given
        Order pending = withStatus(OrderStatus.PENDING_CONFIRMATION);
        givenStored(pending, withStatus(OrderStatus.SOLD));
        when(clockProvider.now()).thenReturn(NOW);
        when(paymentGateway.charge(pending.getOrderId())).thenReturn(Mono.just(PaymentResult.APPROVED));
        givenApplyConflicts(InventoryMovement.SELL);

        // When / Then
        StepVerifier.create(useCase.process(pending.getOrderId()))
                .expectNext(ProcessingOutcome.IGNORED)
                .verifyComplete();
        verify(paymentGateway, never()).refund(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"EXPIRED", "CANCELLED"})
    void shouldRefundWhenAnotherCopyAlreadyReleasedTheOrder(OrderStatus status) {
        // Given
        Order pending = withStatus(OrderStatus.PENDING_CONFIRMATION);
        givenStored(pending, withStatus(status));
        when(clockProvider.now()).thenReturn(NOW);
        when(paymentGateway.charge(pending.getOrderId())).thenReturn(Mono.just(PaymentResult.APPROVED));
        givenApplyConflicts(InventoryMovement.SELL);
        when(paymentGateway.refund(pending.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.process(pending.getOrderId()))
                .expectNext(ProcessingOutcome.IGNORED)
                .verifyComplete();
        verify(paymentGateway).refund(pending.getOrderId());
    }

    @Test
    void shouldSellOnSecondAttemptWhenRereadOrderIsStillPending() {
        // Given
        Order pending = withStatus(OrderStatus.PENDING_CONFIRMATION);
        givenStored(pending, pending);
        when(clockProvider.now()).thenReturn(NOW);
        when(paymentGateway.charge(pending.getOrderId())).thenReturn(Mono.just(PaymentResult.APPROVED));
        when(orderRepository.apply(any(OrderTransition.class), eq(InventoryMovement.SELL)))
                .thenReturn(Mono.error(BusinessErrorMessage.INVALID_STATE_TRANSITION.build()))
                .thenAnswer(invocation -> Mono.just(((OrderTransition) invocation.getArgument(0)).next()));

        // When / Then
        StepVerifier.create(useCase.process(pending.getOrderId()))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        verify(paymentGateway, times(1)).charge(pending.getOrderId());
    }

    @Test
    void shouldIgnoreWhenStatusChangedBeforeTheReread() {
        // Given
        givenStored(reserved, withStatus(OrderStatus.CANCELLED));
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        givenApplyConflicts(InventoryMovement.NONE);

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectNext(ProcessingOutcome.IGNORED)
                .verifyComplete();
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldFailWithInvalidTransitionWhenConflictPersistsAfterReread() {
        // Given
        givenStored(reserved, reserved);
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.findById(EVENT.getEventId())).thenReturn(Mono.just(EVENT));
        givenApplyConflicts(InventoryMovement.NONE);

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.INVALID_STATE_TRANSITION))
                .verify();
        verify(orderRepository, times(2)).apply(any(OrderTransition.class), eq(InventoryMovement.NONE));
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldPropagateErrorWithoutChangingStatusWhenChargeFailsTechnically() {
        // Given
        IllegalStateException failure = new IllegalStateException("gateway down");
        givenStored(withStatus(OrderStatus.PENDING_CONFIRMATION));
        when(clockProvider.now()).thenReturn(NOW);
        when(paymentGateway.charge(reserved.getOrderId())).thenReturn(Mono.error(failure));

        // When / Then
        StepVerifier.create(useCase.process(reserved.getOrderId()))
                .expectErrorMatches(error -> error == failure)
                .verify();
        verify(orderRepository, never()).apply(any(), any());
    }

    private Order withStatus(OrderStatus status) {
        return reserved.toBuilder().status(status).version(2L).build();
    }

    private void givenStored(Order stored) {
        when(orderRepository.findById(reserved.getOrderId())).thenReturn(Mono.just(stored));
    }

    private void givenStored(Order first, Order reread) {
        when(orderRepository.findById(reserved.getOrderId())).thenReturn(Mono.just(first)).thenReturn(Mono.just(reread));
    }

    private void givenApplySucceeds(InventoryMovement movement) {
        when(orderRepository.apply(transitionCaptor.capture(), eq(movement)))
                .thenAnswer(invocation -> Mono.just(((OrderTransition) invocation.getArgument(0)).next()));
    }

    private void givenApplyConflicts(InventoryMovement movement) {
        when(orderRepository.apply(any(OrderTransition.class), eq(movement)))
                .thenReturn(Mono.error(BusinessErrorMessage.INVALID_STATE_TRANSITION.build()));
    }

    private static void assertBusinessError(Throwable error, BusinessErrorMessage expected) {
        assertThat(error).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                .isEqualTo(expected);
    }
}
