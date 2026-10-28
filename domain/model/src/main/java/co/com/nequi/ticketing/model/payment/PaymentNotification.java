package co.com.nequi.ticketing.model.payment;

import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Notice of the payment gateway with the result of the charge of an order. The gateway may send the
 * same notice several times; its {@code eventId} identifies it.
 *
 * @param eventId    the identifier the gateway gives to the notice
 * @param orderId    the order that was charged
 * @param status     the result of the charge
 * @param occurredAt when the gateway decided the result
 */
public record PaymentNotification(UUID eventId, OrderId orderId, PaymentResult status, Instant occurredAt) {

    public PaymentNotification {
        Objects.requireNonNull(eventId, "Event id must not be null");
        Objects.requireNonNull(orderId, "Order id must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(occurredAt, "Occurrence instant must not be null");
    }
}
