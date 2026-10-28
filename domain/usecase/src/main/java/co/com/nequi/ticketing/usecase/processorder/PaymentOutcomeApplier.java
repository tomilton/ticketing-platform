package co.com.nequi.ticketing.usecase.processorder;

import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.INVALID_STATE_TRANSITION;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;

/**
 * Applies the result of a payment to an order waiting for it, with the conditional transitions of
 * the orders: an approved payment sells the tickets while the reservation is valid and otherwise
 * expires the order and refunds the charge; a declined payment cancels the order and releases its
 * tickets. The charge in line of the worker and the notification of the gateway share it, so both
 * paths move the inventory the same way and only one transition wins.
 */
@RequiredArgsConstructor
public class PaymentOutcomeApplier {

    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final ClockProvider clockProvider;

    /**
     * Applies a payment result to an order in {@link OrderStatus#PENDING_CONFIRMATION}.
     *
     * @param pending the order waiting for the payment
     * @param result  the result of the payment
     * @return what happened to the order
     */
    public Mono<ProcessingOutcome> apply(Order pending, PaymentResult result) {
        return switch (result) {
            case APPROVED -> confirm(pending);
            case DECLINED -> cancel(pending, StatusChangeReason.PAYMENT_DECLINED, clockProvider.now());
        };
    }

    /**
     * Expires an order whose reservation ended, only if it is still active. A charged order is refunded.
     *
     * @param order the order
     * @param now   the current instant
     * @return {@link ProcessingOutcome#EXPIRED}, or empty if the order is already final
     */
    public Mono<ProcessingOutcome> expireIfActive(Order order, Instant now) {
        return Mono.just(order)
                .filter(candidate -> candidate.getStatus().isActive())
                .flatMap(active -> expire(active, now));
    }

    /**
     * Cancels an active order and releases its tickets.
     *
     * @param active the active order
     * @param reason why the order is cancelled
     * @param now    the current instant
     * @return {@link ProcessingOutcome#CANCELLED}
     */
    public Mono<ProcessingOutcome> cancel(Order active, StatusChangeReason reason, Instant now) {
        return orderRepository.apply(active.transitionTo(OrderStatus.CANCELLED, reason, now), InventoryMovement.RELEASE)
                .thenReturn(ProcessingOutcome.CANCELLED);
    }

    /**
     * Tells whether an error is the rejection of a transition because the order changed meanwhile.
     *
     * @param error the error of a transition
     * @return {@code true} for {@code INVALID_STATE_TRANSITION}
     */
    public static Boolean isConflict(Throwable error) {
        return error instanceof BusinessException business
                && business.getBusinessErrorMessage() == INVALID_STATE_TRANSITION;
    }

    private Mono<ProcessingOutcome> confirm(Order pending) {
        return Mono.fromSupplier(clockProvider::now)
                .flatMap(now -> Mono.just(pending)
                        .filter(order -> order.isReservationValid(now))
                        .flatMap(order -> sell(order, now))
                        .switchIfEmpty(Mono.defer(() -> expire(pending, now))));
    }

    private Mono<ProcessingOutcome> sell(Order pending, Instant now) {
        return orderRepository.apply(pending.transitionTo(OrderStatus.SOLD, StatusChangeReason.PAYMENT_APPROVED, now),
                        InventoryMovement.SELL)
                .thenReturn(ProcessingOutcome.SOLD)
                .onErrorResume(PaymentOutcomeApplier::isConflict,
                        conflict -> load(pending.getOrderId()).flatMap(this::settleApprovedPayment));
    }

    /**
     * Resolves an approved payment whose sale was rejected because the order changed. The charge
     * exists, so it is refunded unless the order ended sold.
     */
    private Mono<ProcessingOutcome> settleApprovedPayment(Order reread) {
        Instant now = clockProvider.now();
        return switch (reread.effectiveStatus(now)) {
            case SOLD, COMPLIMENTARY -> Mono.just(ProcessingOutcome.IGNORED);
            case RESERVED, PENDING_CONFIRMATION -> orderRepository.apply(reread.transitionTo(OrderStatus.SOLD,
                    StatusChangeReason.PAYMENT_APPROVED, now), InventoryMovement.SELL).thenReturn(ProcessingOutcome.SOLD);
            case EXPIRED -> expireIfActive(reread, now).switchIfEmpty(Mono.defer(() -> refund(reread)));
            case CANCELLED -> refund(reread);
        };
    }

    private Mono<ProcessingOutcome> expire(Order active, Instant now) {
        boolean charged = active.getStatus() == OrderStatus.PENDING_CONFIRMATION;
        StatusChangeReason reason = charged ? StatusChangeReason.PAYMENT_AFTER_EXPIRY
                : StatusChangeReason.RESERVATION_EXPIRED;
        return orderRepository.apply(active.transitionTo(OrderStatus.EXPIRED, reason, now), InventoryMovement.RELEASE)
                .flatMap(expired -> Mono.just(charged)
                        .filter(Boolean::booleanValue)
                        .flatMap(refundNeeded -> paymentGateway.refund(active.getOrderId()))
                        .thenReturn(ProcessingOutcome.EXPIRED));
    }

    private Mono<ProcessingOutcome> refund(Order order) {
        return paymentGateway.refund(order.getOrderId()).thenReturn(ProcessingOutcome.IGNORED);
    }

    private Mono<Order> load(OrderId orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.defer(() -> Mono.error(ORDER_NOT_FOUND.build())));
    }
}
