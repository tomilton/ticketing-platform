package co.com.nequi.ticketing.usecase.confirmpayment;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderTransition;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmPaymentUseCaseTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Order RESERVED = Order.place(OrderId.newId(), new CustomerId(UUID.randomUUID()),
            EventId.newId(), new Quantity(2), 0, CREATED_AT, TTL);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Test
    void shouldSellWhenApprovedNoticeArrivesForValidPendingOrder() {
        // Given
        givenStored(withStatus(OrderStatus.PENDING_CONFIRMATION));
        givenApplySucceeds(InventoryMovement.SELL);

        // When / Then
        StepVerifier.create(useCase(CREATED_AT.plusSeconds(60)).confirm(notice(PaymentResult.APPROVED)))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
    }

    @Test
    void shouldCancelAndReleaseWhenDeclinedNoticeArrivesForPendingOrder() {
        // Given
        givenStored(withStatus(OrderStatus.PENDING_CONFIRMATION));
        givenApplySucceeds(InventoryMovement.RELEASE);

        // When / Then
        StepVerifier.create(useCase(CREATED_AT.plusSeconds(60)).confirm(notice(PaymentResult.DECLINED)))
                .expectNext(ProcessingOutcome.CANCELLED)
                .verifyComplete();
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldExpireReleaseAndRefundWhenApprovedNoticeArrivesForExpiredPendingOrder() {
        // Given
        givenStored(withStatus(OrderStatus.PENDING_CONFIRMATION));
        givenApplySucceeds(InventoryMovement.RELEASE);
        when(paymentGateway.refund(RESERVED.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase(CREATED_AT.plus(TTL)).confirm(notice(PaymentResult.APPROVED)))
                .expectNext(ProcessingOutcome.EXPIRED)
                .verifyComplete();
    }

    @Test
    void shouldDeferWithoutChangesWhenOrderWasNotChargedYet() {
        // Given
        givenStored(RESERVED);

        // When / Then
        StepVerifier.create(useCase(CREATED_AT.plusSeconds(60)).confirm(notice(PaymentResult.APPROVED)))
                .expectNext(ProcessingOutcome.DEFERRED)
                .verifyComplete();
        verify(orderRepository, never()).apply(any(), any());
        verifyNoInteractions(paymentGateway);
    }

    @Test
    void shouldFailWithOrderNotFoundWhenOrderDoesNotExist() {
        // Given
        when(orderRepository.findById(RESERVED.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase(CREATED_AT).confirm(notice(PaymentResult.APPROVED)))
                .expectErrorSatisfies(error -> assertThat(((BusinessException) error).getBusinessErrorMessage())
                        .isEqualTo(BusinessErrorMessage.ORDER_NOT_FOUND))
                .verify();
    }

    private ConfirmPaymentUseCase useCase(Instant now) {
        return new ConfirmPaymentUseCase(orderRepository, paymentGateway, () -> now);
    }

    private static PaymentNotification notice(PaymentResult result) {
        return new PaymentNotification(UUID.randomUUID(), RESERVED.getOrderId(), result, CREATED_AT.plusSeconds(30));
    }

    private static Order withStatus(OrderStatus status) {
        return RESERVED.toBuilder().status(status).version(2L).build();
    }

    private void givenStored(Order stored) {
        when(orderRepository.findById(RESERVED.getOrderId())).thenReturn(Mono.just(stored));
    }

    private void givenApplySucceeds(InventoryMovement movement) {
        when(orderRepository.apply(any(OrderTransition.class), eq(movement)))
                .thenAnswer(invocation -> Mono.just(((OrderTransition) invocation.getArgument(0)).next()));
    }
}
