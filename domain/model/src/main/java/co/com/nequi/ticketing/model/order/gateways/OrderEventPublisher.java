package co.com.nequi.ticketing.model.order.gateways;

import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import reactor.core.publisher.Mono;

/**
 * Publication of the order events for their asynchronous processing.
 */
public interface OrderEventPublisher {

    /**
     * Publishes that an order was accepted.
     *
     * @param message the notice of the accepted order
     * @return completion when the message is accepted by the queue
     */
    Mono<Void> publishOrderPlaced(OrderPlacedMessage message);
}
