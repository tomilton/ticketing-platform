package co.com.nequi.ticketing.model.idempotency;

import co.com.nequi.ticketing.model.shared.CustomerId;

import java.util.Objects;

/**
 * Owner of an idempotency key: a key is unique only inside its scope. A customer buys in its own
 * scope and an administrator grants complimentary tickets in a separate one, so the same key never
 * mixes a purchase with a grant.
 *
 * @param value the stored form of the scope: the UUID of the customer, or {@code ADMIN#} and the UUID
 *              of the administrator
 */
public record IdempotencyScope(String value) {

    private static final String ADMINISTRATOR_PREFIX = "ADMIN#";

    public IdempotencyScope {
        Objects.requireNonNull(value, "Scope value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Scope value must not be blank");
        }
    }

    /**
     * Scope of the purchases of a customer.
     *
     * @param customerId the customer
     * @return the scope of the customer
     */
    public static IdempotencyScope ofCustomer(CustomerId customerId) {
        return new IdempotencyScope(customerId.value().toString());
    }

    /**
     * Scope of the grants of an administrator.
     *
     * @param administratorId the administrator
     * @return the scope of the administrator
     */
    public static IdempotencyScope ofAdministrator(CustomerId administratorId) {
        return new IdempotencyScope(ADMINISTRATOR_PREFIX + administratorId.value());
    }
}
