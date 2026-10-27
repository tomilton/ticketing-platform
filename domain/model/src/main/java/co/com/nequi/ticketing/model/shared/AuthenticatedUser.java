package co.com.nequi.ticketing.model.shared;

import java.util.Objects;

/**
 * Caller of an operation, as the entry point verified it. The domain never sees how the identity was
 * proven.
 *
 * @param id    the customer the caller acts as
 * @param admin whether the caller holds the administrator role
 */
public record AuthenticatedUser(CustomerId id, Boolean admin) {

    public AuthenticatedUser {
        Objects.requireNonNull(id, "Authenticated user id must not be null");
        Objects.requireNonNull(admin, "Authenticated user admin flag must not be null");
    }
}
