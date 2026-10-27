package co.com.nequi.ticketing.model.order.gateways;

import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import reactor.core.publisher.Mono;

/**
 * Payment of the orders. Both operations are idempotent by order: repeating them never charges or
 * refunds twice.
 */
public interface PaymentGateway {

    /**
     * Charges an order.
     *
     * @param orderId the order, used as the idempotency key of the charge
     * @return the answer of the gateway; the same order always gets the same answer
     */
    Mono<PaymentResult> charge(OrderId orderId);

    /**
     * Refunds the charge of an order. It has no effect if the order was not charged.
     *
     * @param orderId the order
     * @return completion when the refund is registered
     */
    Mono<Void> refund(OrderId orderId);
}
