package co.com.nequi.ticketing.model.order;

import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.shared.CustomerId;

import java.util.Objects;
import java.util.UUID;

/**
 * What the grant needs besides the order: the administrator who grants it, for the audit, and the
 * idempotency key that the same atomic write closes.
 *
 * @param administratorId the administrator who grants the tickets
 * @param scope           the scope of the key
 * @param key             the idempotency key of the request
 * @param lockToken       the token that proves the request holds the key
 */
public record GrantContext(CustomerId administratorId, IdempotencyScope scope, IdempotencyKey key, UUID lockToken) {

    public GrantContext {
        Objects.requireNonNull(administratorId, "Administrator id must not be null");
        Objects.requireNonNull(scope, "Scope must not be null");
        Objects.requireNonNull(key, "Idempotency key must not be null");
        Objects.requireNonNull(lockToken, "Lock token must not be null");
    }
}
