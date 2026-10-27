package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.shared.CustomerId;

import java.util.Objects;
import java.util.UUID;

/**
 * What the reservation needs besides the order: the limit of the customer and the idempotency key
 * that the same atomic write closes.
 *
 * @param customerId            the customer
 * @param key                   the idempotency key of the request
 * @param lockToken             the token that proves the request holds the key
 * @param maxTicketsPerCustomer the tickets the customer can hold for the event
 */
public record ReservationContext(CustomerId customerId, IdempotencyKey key, UUID lockToken,
                                 Integer maxTicketsPerCustomer) {

    public ReservationContext {
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(key, "Idempotency key must not be null");
        Objects.requireNonNull(lockToken, "Lock token must not be null");
        Objects.requireNonNull(maxTicketsPerCustomer, "Max tickets per customer must not be null");
    }
}
