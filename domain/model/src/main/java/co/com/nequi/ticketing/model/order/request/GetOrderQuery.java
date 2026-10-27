package co.com.nequi.ticketing.model.order.request;

import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.shared.CustomerId;

import java.util.Objects;

/**
 * Request of a customer to read one of its orders.
 *
 * @param customerId the customer who asks
 * @param orderId    the order
 */
public record GetOrderQuery(CustomerId customerId, OrderId orderId) {

    public GetOrderQuery {
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(orderId, "Order id must not be null");
    }
}
