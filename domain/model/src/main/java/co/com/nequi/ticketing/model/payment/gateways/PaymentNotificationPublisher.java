package co.com.nequi.ticketing.model.payment.gateways;

import co.com.nequi.ticketing.model.payment.PaymentNotification;
import reactor.core.publisher.Mono;

/**
 * Publication of the notices of the payment gateway for the worker, which applies them to the orders.
 */
public interface PaymentNotificationPublisher {

    /**
     * Publishes a notice.
     *
     * @param notification the notice
     * @return completion when the notice is accepted by the queue
     */
    Mono<Void> publish(PaymentNotification notification);
}
