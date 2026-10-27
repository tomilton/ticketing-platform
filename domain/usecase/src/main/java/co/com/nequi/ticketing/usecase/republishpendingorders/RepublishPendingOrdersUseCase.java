package co.com.nequi.ticketing.usecase.republishpendingorders;

import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes again the accepted orders whose publication failed, so no accepted order is left
 * unprocessed. Only orders older than the grace period are taken, to leave time to the publication
 * of the purchase itself.
 */
@RequiredArgsConstructor
public class RepublishPendingOrdersUseCase {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ClockProvider clockProvider;
    private final OrderSettings orderSettings;

    /**
     * Publishes the pending orders. A failed order does not stop the others; the next cycle tries it
     * again.
     *
     * @return how many orders were published and marked
     */
    public Mono<Long> republish() {
        return Mono.fromSupplier(clockProvider::now)
                .flatMapMany(now -> orderRepository.findPendingPublication(now.minus(orderSettings.republishGrace()))
                        .flatMap(orderId -> orderRepository.findById(orderId)
                                        .flatMap(order -> publish(order, now))
                                        .onErrorResume(error -> Mono.empty()),
                                orderSettings.republishConcurrency()))
                .count();
    }

    private Mono<Order> publish(Order order, Instant now) {
        OrderPlacedMessage message = new OrderPlacedMessage(UUID.randomUUID(), order.getOrderId(),
                order.getEventId(), new Quantity(order.getQuantity()), now);
        return orderEventPublisher.publishOrderPlaced(message)
                .then(Mono.defer(() -> orderRepository.markPublished(order.getOrderId(), now)))
                .thenReturn(order);
    }
}
