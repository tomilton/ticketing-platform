package co.com.nequi.ticketing.model.order.request;

import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.shared.AuthenticatedUser;

import java.util.Objects;

/**
 * Request to read an order: a customer reads its own orders and an administrator reads any.
 *
 * @param requester the caller who asks
 * @param orderId   the order
 */
public record GetOrderQuery(AuthenticatedUser requester, OrderId orderId) {

    public GetOrderQuery {
        Objects.requireNonNull(requester, "Requester must not be null");
        Objects.requireNonNull(orderId, "Order id must not be null");
    }
}
