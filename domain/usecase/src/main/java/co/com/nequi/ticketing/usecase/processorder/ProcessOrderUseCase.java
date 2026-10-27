package co.com.nequi.ticketing.usecase.processorder;

import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
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
 * Processes an accepted order by its current status, not by the message that triggered it, so a
 * duplicated or late message never has a second effect. It asks for the payment and sells the
 * tickets only while the reservation is valid; an expired or cancelled order gives its tickets back.
 * A version conflict is evaluated again once from a fresh read.
 */
@RequiredArgsConstructor
public class ProcessOrderUseCase {

    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final PaymentGateway paymentGateway;
    private final ClockProvider clockProvider;

    public Mono<ProcessingOutcome> process(OrderId orderId) {
        return load(orderId)
                .flatMap(order -> evaluate(order)
                        .onErrorResume(ProcessOrderUseCase::isConflict, conflict -> load(orderId).flatMap(this::evaluate)));
    }

    private Mono<Order> load(OrderId orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.defer(() -> Mono.error(ORDER_NOT_FOUND.build())));
    }

    private Mono<ProcessingOutcome> evaluate(Order order) {
        Instant now = clockProvider.now();
        return switch (order.effectiveStatus(now)) {
            case RESERVED -> processReserved(order, now);
            case PENDING_CONFIRMATION -> requestPayment(order);
            case EXPIRED -> expireIfActive(order, now).defaultIfEmpty(ProcessingOutcome.IGNORED);
            case SOLD, CANCELLED, COMPLIMENTARY -> Mono.just(ProcessingOutcome.IGNORED);
        };
    }

    private Mono<ProcessingOutcome> processReserved(Order order, Instant now) {
        return eventRepository.findById(order.getEventId())
                .filter(event -> event.isListable(now))
                .flatMap(event -> orderRepository.apply(order.transitionTo(OrderStatus.PENDING_CONFIRMATION,
                        StatusChangeReason.PAYMENT_REQUESTED, now), InventoryMovement.NONE))
                .flatMap(this::requestPayment)
                .switchIfEmpty(Mono.defer(() -> cancel(order, StatusChangeReason.EVENT_NOT_ACTIVE, now)));
    }

    private Mono<ProcessingOutcome> requestPayment(Order pending) {
        return paymentGateway.charge(pending.getOrderId())
                .flatMap(result -> switch (result) {
                    case APPROVED -> confirm(pending);
                    case DECLINED -> cancel(pending, StatusChangeReason.PAYMENT_DECLINED, clockProvider.now());
                });
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
                .onErrorResume(ProcessOrderUseCase::isConflict,
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

    private Mono<ProcessingOutcome> expireIfActive(Order order, Instant now) {
        return Mono.just(order)
                .filter(candidate -> candidate.getStatus().isActive())
                .flatMap(active -> expire(active, now));
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

    private Mono<ProcessingOutcome> cancel(Order active, StatusChangeReason reason, Instant now) {
        return orderRepository.apply(active.transitionTo(OrderStatus.CANCELLED, reason, now), InventoryMovement.RELEASE)
                .thenReturn(ProcessingOutcome.CANCELLED);
    }

    private Mono<ProcessingOutcome> refund(Order order) {
        return paymentGateway.refund(order.getOrderId()).thenReturn(ProcessingOutcome.IGNORED);
    }

    private static boolean isConflict(Throwable error) {
        return error instanceof BusinessException business
                && business.getBusinessErrorMessage() == INVALID_STATE_TRANSITION;
    }
}
