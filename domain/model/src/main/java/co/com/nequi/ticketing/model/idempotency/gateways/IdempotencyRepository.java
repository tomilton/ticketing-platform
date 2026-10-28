package co.com.nequi.ticketing.model.idempotency.gateways;

import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistence of the idempotency keys of the requests that create orders. A key belongs to one scope.
 */
public interface IdempotencyRepository {

    /**
     * Claims a key for a request. A key whose lease ended is claimed again.
     *
     * @param scope       the owner of the key
     * @param key         the key
     * @param fingerprint the content of the request
     * @param now         the current instant
     * @return {@link IdempotencyClaim.Granted} if the request now holds the key, or
     * {@link IdempotencyClaim.Existing} with the stored state otherwise
     */
    Mono<IdempotencyClaim> claim(IdempotencyScope scope, IdempotencyKey key, RequestFingerprint fingerprint,
                                 Instant now);

    /**
     * Closes a key with a definitive outcome, only if the request still holds it.
     *
     * @param scope      the owner of the key
     * @param key        the key
     * @param lockToken  the token of the request
     * @param outcome    the definitive outcome
     * @return completion when the key is closed
     */
    Mono<Void> complete(IdempotencyScope scope, IdempotencyKey key, UUID lockToken, PlacementOutcome outcome);

    /**
     * Frees a key so that a retry tries again. It never deletes a closed key: if the request no
     * longer holds the key, it does nothing.
     *
     * @param scope      the owner of the key
     * @param key        the key
     * @param lockToken  the token of the request
     * @return completion when the key is free or was already closed
     */
    Mono<Void> release(IdempotencyScope scope, IdempotencyKey key, UUID lockToken);
}
