package co.com.nequi.ticketing.model.idempotency;

import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.shared.CustomerId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stored state of an idempotency key of a customer.
 *
 * @param customerId  the owner of the key
 * @param key         the key chosen by the customer
 * @param fingerprint the content of the first request with the key
 * @param status      whether a request still holds the key or it was closed
 * @param lockToken   the token of the request that holds the key
 * @param lockedUntil the end of the lease of the request that holds the key
 * @param outcome     the definitive result, {@code null} while the key is in progress
 */
public record IdempotencyRecord(CustomerId customerId, IdempotencyKey key, RequestFingerprint fingerprint,
                                IdempotencyStatus status, UUID lockToken, Instant lockedUntil,
                                PlacementOutcome outcome) {

    public IdempotencyRecord {
        Objects.requireNonNull(customerId, "Customer id must not be null");
        Objects.requireNonNull(key, "Key must not be null");
        Objects.requireNonNull(fingerprint, "Fingerprint must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(lockToken, "Lock token must not be null");
        Objects.requireNonNull(lockedUntil, "Lock end must not be null");
    }

    /**
     * Tells whether a request has the same content as the first one.
     *
     * @param candidate the fingerprint of the request
     * @return {@code true} if both fingerprints are equal
     */
    public boolean matches(RequestFingerprint candidate) {
        return fingerprint.equals(candidate);
    }

    /**
     * Tells whether the request that holds the key is still within its lease.
     *
     * @param now the current instant
     * @return {@code true} if the key is in progress and {@code now} is before {@code lockedUntil}
     */
    public boolean isLeaseActive(Instant now) {
        return status == IdempotencyStatus.IN_PROGRESS && now.isBefore(lockedUntil);
    }
}
