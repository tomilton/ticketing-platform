package co.com.nequi.ticketing.model.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * Key chosen by the customer to make a purchase request safe to repeat.
 *
 * @param value the UUID that identifies the request
 */
public record IdempotencyKey(UUID value) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "Idempotency key value must not be null");
    }
}
