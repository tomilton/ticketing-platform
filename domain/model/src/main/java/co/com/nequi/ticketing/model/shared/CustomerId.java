package co.com.nequi.ticketing.model.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of the customer who places and owns an order.
 *
 * @param value the UUID that identifies the customer
 */
public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "Customer id value must not be null");
    }
}
