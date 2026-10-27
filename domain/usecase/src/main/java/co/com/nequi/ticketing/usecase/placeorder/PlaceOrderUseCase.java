package co.com.nequi.ticketing.usecase.placeorder;

import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyRecord;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.PlacementOutcome.Accepted;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_ACTIVE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.IDEMPOTENCY_KEY_REUSED;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.REQUEST_IN_PROGRESS;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKET_LIMIT_EXCEEDED;

/**
 * Reserves the tickets of an active event and accepts the order without waiting for the payment.
 * The idempotency key is claimed first, so a repetition gets exactly the same accepted order or the
 * same rejection; a technical error frees the key so that a retry tries again.
 */
@RequiredArgsConstructor
public class PlaceOrderUseCase {

    private final IdempotencyRepository idempotencyRepository;
    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ClockProvider clockProvider;
    private final OrderSettings orderSettings;

    public Mono<Accepted> place(PlaceOrderCommand command) {
        RequestFingerprint fingerprint = RequestFingerprint.of(command.eventId(), command.quantity());
        IdempotencyScope scope = IdempotencyScope.ofCustomer(command.customerId());
        return Mono.fromSupplier(clockProvider::now)
                .flatMap(now -> idempotencyRepository.claim(scope, command.key(), fingerprint, now)
                        .flatMap(claim -> switch (claim) {
                            case IdempotencyClaim.Granted granted -> placeClaimed(command, granted.lockToken(), now);
                            case IdempotencyClaim.Existing existing -> repeat(existing.record(), fingerprint);
                        }));
    }

    private Mono<Accepted> repeat(IdempotencyRecord stored, RequestFingerprint fingerprint) {
        return Mono.just(stored)
                .filter(candidate -> candidate.matches(fingerprint))
                .switchIfEmpty(Mono.defer(() -> Mono.error(IDEMPOTENCY_KEY_REUSED.build())))
                .filter(candidate -> candidate.status() == IdempotencyStatus.COMPLETED)
                .switchIfEmpty(Mono.defer(() -> Mono.error(REQUEST_IN_PROGRESS.build())))
                .flatMap(completed -> replay(completed.outcome()));
    }

    private Mono<Accepted> replay(PlacementOutcome outcome) {
        return switch (outcome) {
            case Accepted accepted -> Mono.just(accepted);
            case PlacementOutcome.Rejected rejected -> Mono.error(rejected.error().build());
        };
    }

    private Mono<Accepted> placeClaimed(PlaceOrderCommand command, UUID lockToken, Instant now) {
        return reserve(command, lockToken, now)
                .onErrorResume(PlaceOrderUseCase::isDefinitiveRejection, error -> idempotencyRepository
                        .complete(IdempotencyScope.ofCustomer(command.customerId()), command.key(), lockToken,
                                new PlacementOutcome.Rejected(((BusinessException) error).getBusinessErrorMessage()))
                        .then(Mono.error(error)))
                .onErrorResume(error -> !(error instanceof BusinessException), error -> idempotencyRepository
                        .release(IdempotencyScope.ofCustomer(command.customerId()), command.key(), lockToken)
                        .then(Mono.error(error)))
                .flatMap(order -> publish(order, now).thenReturn(Accepted.of(order)));
    }

    private Mono<Order> reserve(PlaceOrderCommand command, UUID lockToken, Instant now) {
        return Mono.just(command)
                .filter(candidate -> candidate.quantity().value() <= orderSettings.maxTicketsPerOrder())
                .switchIfEmpty(Mono.defer(() -> Mono.error(TICKET_LIMIT_EXCEEDED.build())))
                .flatMap(valid -> eventRepository.findById(valid.eventId()))
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_FOUND.build())))
                .filter(event -> event.isListable(now))
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_ACTIVE.build())))
                .flatMap(event -> orderRepository.reserve(newOrder(command, event, now), event,
                        new ReservationContext(command.customerId(), command.key(), lockToken,
                                orderSettings.maxTicketsPerCustomer()), now));
    }

    private Order newOrder(PlaceOrderCommand command, Event event, Instant now) {
        Integer firstShard = ThreadLocalRandom.current().nextInt(event.getShardCount());
        return Order.place(OrderId.newId(), command.customerId(), command.eventId(), command.quantity(), firstShard,
                now, orderSettings.reservationTtl());
    }

    /**
     * Publishes the accepted order and marks it as published. A failure never fails the purchase:
     * the order already belongs to the customer and the republication sends it later. The adapter
     * logs the failure with the order id.
     */
    private Mono<Void> publish(Order order, Instant now) {
        OrderPlacedMessage message = new OrderPlacedMessage(UUID.randomUUID(), order.getOrderId(),
                order.getEventId(), new Quantity(order.getQuantity()), order.getCreatedAt());
        return orderEventPublisher.publishOrderPlaced(message)
                .then(Mono.defer(() -> orderRepository.markPublished(order.getOrderId(), now)))
                .onErrorResume(error -> Mono.empty());
    }

    private static boolean isDefinitiveRejection(Throwable error) {
        return error instanceof BusinessException business
                && business.getBusinessErrorMessage() != REQUEST_IN_PROGRESS;
    }
}
