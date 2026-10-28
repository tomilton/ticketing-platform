package co.com.nequi.ticketing.usecase.processorder;

import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;

/**
 * Processes an accepted order by its current status, not by the message that triggered it, so a
 * duplicated or late message never has a second effect. It asks for the payment and sells the
 * tickets only while the reservation is valid; an expired or cancelled order gives its tickets back.
 * A version conflict is evaluated again once from a fresh read. The result of the payment is applied
 * by {@link PaymentOutcomeApplier}.
 */
public class ProcessOrderUseCase {

    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final PaymentGateway paymentGateway;
    private final ClockProvider clockProvider;
    private final PaymentOutcomeApplier paymentOutcomeApplier;

    public ProcessOrderUseCase(OrderRepository orderRepository, EventRepository eventRepository,
                               PaymentGateway paymentGateway, ClockProvider clockProvider) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.paymentGateway = paymentGateway;
        this.clockProvider = clockProvider;
        this.paymentOutcomeApplier = new PaymentOutcomeApplier(orderRepository, paymentGateway, clockProvider);
    }

    public Mono<ProcessingOutcome> process(OrderId orderId) {
        return load(orderId)
                .flatMap(order -> evaluate(order)
                        .onErrorResume(PaymentOutcomeApplier::isConflict, conflict -> load(orderId).flatMap(this::evaluate)));
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
            case EXPIRED -> paymentOutcomeApplier.expireIfActive(order, now).defaultIfEmpty(ProcessingOutcome.IGNORED);
            case SOLD, CANCELLED, COMPLIMENTARY -> Mono.just(ProcessingOutcome.IGNORED);
        };
    }

    private Mono<ProcessingOutcome> processReserved(Order order, Instant now) {
        return eventRepository.findById(order.getEventId())
                .filter(event -> event.isListable(now))
                .flatMap(event -> orderRepository.apply(order.transitionTo(OrderStatus.PENDING_CONFIRMATION,
                        StatusChangeReason.PAYMENT_REQUESTED, now), InventoryMovement.NONE))
                .flatMap(this::requestPayment)
                .switchIfEmpty(Mono.defer(() -> paymentOutcomeApplier.cancel(order, StatusChangeReason.EVENT_NOT_ACTIVE,
                        now)));
    }

    private Mono<ProcessingOutcome> requestPayment(Order pending) {
        return paymentGateway.charge(pending.getOrderId())
                .flatMap(result -> paymentOutcomeApplier.apply(pending, result));
    }
}
