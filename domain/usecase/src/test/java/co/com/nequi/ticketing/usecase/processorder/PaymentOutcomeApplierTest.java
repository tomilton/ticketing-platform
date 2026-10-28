package co.com.nequi.ticketing.usecase.processorder;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderTransition;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOutcomeApplierTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private ClockProvider clockProvider;

    @Captor
    private ArgumentCaptor<OrderTransition> transitionCaptor;

    private PaymentOutcomeApplier applier;

    private Order pending;

    @BeforeEach
    void setUp() {
        applier = new PaymentOutcomeApplier(orderRepository, paymentGateway, clockProvider);
        pending = Order.place(OrderId.newId(), new CustomerId(UUID.randomUUID()), EventId.newId(), new Quantity(2), 0,
                CREATED_AT, TTL).toBuilder().status(OrderStatus.PENDING_CONFIRMATION).version(2L).build();
    }

    @Test
    void shouldSellWhenApprovedPaymentArrivesWithinReservation() {
        // Given
        when(clockProvider.now()).thenReturn(CREATED_AT.plusSeconds(60));
        givenApplySucceeds(InventoryMovement.SELL);

        // When / Then
        StepVerifier.create(applier.apply(pending, PaymentResult.APPROVED))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().to()).isEqualTo(OrderStatus.SOLD);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldExpireReleaseAndRefundWhenApprovedPaymentArrivesAfterReservation() {
        // Given
        when(clockProvider.now()).thenReturn(CREATED_AT.plus(TTL));
        givenApplySucceeds(InventoryMovement.RELEASE);
        when(paymentGateway.refund(pending.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(applier.apply(pending, PaymentResult.APPROVED))
                .expectNext(ProcessingOutcome.EXPIRED)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().reason()).isEqualTo(StatusChangeReason.PAYMENT_AFTER_EXPIRY);
    }

    @Test
    void shouldCancelAndReleaseWhenPaymentIsDeclined() {
        // Given
        when(clockProvider.now()).thenReturn(CREATED_AT.plusSeconds(60));
        givenApplySucceeds(InventoryMovement.RELEASE);

        // When / Then
        StepVerifier.create(applier.apply(pending, PaymentResult.DECLINED))
                .expectNext(ProcessingOutcome.CANCELLED)
                .verifyComplete();
        assertThat(transitionCaptor.getValue().change().reason()).isEqualTo(StatusChangeReason.PAYMENT_DECLINED);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldIgnoreWithoutRefundWhenAnotherPathAlreadySoldTheOrder() {
        // Given
        when(clockProvider.now()).thenReturn(CREATED_AT.plusSeconds(60));
        when(orderRepository.apply(any(OrderTransition.class), eq(InventoryMovement.SELL)))
                .thenReturn(Mono.error(BusinessErrorMessage.INVALID_STATE_TRANSITION.build()));
        when(orderRepository.findById(pending.getOrderId()))
                .thenReturn(Mono.just(pending.toBuilder().status(OrderStatus.SOLD).version(3L).build()));

        // When / Then
        StepVerifier.create(applier.apply(pending, PaymentResult.APPROVED))
                .expectNext(ProcessingOutcome.IGNORED)
                .verifyComplete();
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldRefundWhenAnotherPathAlreadyCancelledTheOrder() {
        // Given
        when(clockProvider.now()).thenReturn(CREATED_AT.plusSeconds(60));
        when(orderRepository.apply(any(OrderTransition.class), eq(InventoryMovement.SELL)))
                .thenReturn(Mono.error(BusinessErrorMessage.INVALID_STATE_TRANSITION.build()));
        when(orderRepository.findById(pending.getOrderId()))
                .thenReturn(Mono.just(pending.toBuilder().status(OrderStatus.CANCELLED).version(3L).build()));
        when(paymentGateway.refund(pending.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(applier.apply(pending, PaymentResult.APPROVED))
                .expectNext(ProcessingOutcome.IGNORED)
                .verifyComplete();
        verify(paymentGateway).refund(pending.getOrderId());
    }

    @Test
    void shouldDoNothingWhenOrderToExpireIsAlreadyFinal() {
        // When / Then
        StepVerifier.create(applier.expireIfActive(pending.toBuilder().status(OrderStatus.SOLD).build(),
                        CREATED_AT.plus(TTL)))
                .verifyComplete();
        verifyNoInteractions(orderRepository, paymentGateway);
    }

    @Test
    void shouldRecognizeOnlyInvalidTransitionAsConflict() {
        // When / Then
        assertThat(PaymentOutcomeApplier.isConflict(BusinessErrorMessage.INVALID_STATE_TRANSITION.build())).isTrue();
        assertThat(PaymentOutcomeApplier.isConflict(BusinessErrorMessage.ORDER_NOT_FOUND.build())).isFalse();
        assertThat(PaymentOutcomeApplier.isConflict(new IllegalStateException("other"))).isFalse();
    }

    private void givenApplySucceeds(InventoryMovement movement) {
        when(orderRepository.apply(transitionCaptor.capture(), eq(movement)))
                .thenAnswer(invocation -> Mono.just(((OrderTransition) invocation.getArgument(0)).next()));
    }
}
