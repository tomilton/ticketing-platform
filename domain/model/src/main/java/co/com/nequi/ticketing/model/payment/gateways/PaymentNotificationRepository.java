package co.com.nequi.ticketing.model.payment.gateways;

import co.com.nequi.ticketing.model.payment.PaymentNotification;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Register of the notices of the payment gateway already received, to drop their copies.
 */
public interface PaymentNotificationRepository {

    /**
     * Tells whether a notice was already received.
     *
     * @param eventId the identifier of the notice
     * @return {@code true} if the notice is registered
     */
    Mono<Boolean> exists(UUID eventId);

    /**
     * Registers a received notice. Registering a notice that already exists is not an error.
     *
     * @param notification the notice
     * @param expiresAt    when the register of the notice is deleted
     * @return completion when the notice is registered
     */
    Mono<Void> register(PaymentNotification notification, Instant expiresAt);
}
