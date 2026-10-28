package co.com.nequi.ticketing.usecase.confirmpayment;

import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.usecase.processorder.PaymentOutcomeApplier;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;

/**
 * Applies a notice of the payment gateway by the current status of the order, with the same
 * conditional transitions as the charge in line, so a copy of the notice or a race with the worker
 * moves the inventory only once. An order still {@code RESERVED} was not charged yet: the notice is
 * deferred to a later delivery. A final order does not change, but an approved charge of an order that
 * expired or was cancelled is refunded. A lost race is evaluated again once from a fresh read.
 */
public class ConfirmPaymentUseCase {

    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentOutcomeApplier paymentOutcomeApplier;

    public ConfirmPaymentUseCase(OrderRepository orderRepository, PaymentGateway paymentGateway,
                                 ClockProvider clockProvider) {
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
        this.paymentOutcomeApplier = new PaymentOutcomeApplier(orderRepository, paymentGateway, clockProvider);
    }

    public Mono<ProcessingOutcome> confirm(PaymentNotification notification) {
        return load(notification.orderId())
                .flatMap(order -> evaluate(order, notification.status())
                        .onErrorResume(PaymentOutcomeApplier::isConflict, conflict -> load(notification.orderId())
                                .flatMap(reread -> evaluate(reread, notification.status()))));
    }

    private Mono<ProcessingOutcome> evaluate(Order order, PaymentResult result) {
        return switch (order.getStatus()) {
            case RESERVED -> Mono.just(ProcessingOutcome.DEFERRED);
            case PENDING_CONFIRMATION -> paymentOutcomeApplier.apply(order, result);
            case EXPIRED, CANCELLED -> result == PaymentResult.APPROVED
                    ? paymentGateway.refund(order.getOrderId()).thenReturn(ProcessingOutcome.IGNORED)
                    : Mono.just(ProcessingOutcome.IGNORED);
            case SOLD, COMPLIMENTARY -> Mono.just(ProcessingOutcome.IGNORED);
        };
    }

    private Mono<Order> load(OrderId orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.defer(() -> Mono.error(ORDER_NOT_FOUND.build())));
    }
}
