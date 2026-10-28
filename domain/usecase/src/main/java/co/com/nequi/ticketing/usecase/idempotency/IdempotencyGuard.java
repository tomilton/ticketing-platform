package co.com.nequi.ticketing.usecase.idempotency;

import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyRecord;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.IDEMPOTENCY_KEY_REUSED;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.REQUEST_IN_PROGRESS;

/**
 * Rules shared by the requests that create an order under an idempotency key: a repetition gets
 * exactly the stored outcome, a definitive rejection closes the key with it, and a technical error
 * frees the key so that a retry tries again.
 */
@UtilityClass
public class IdempotencyGuard {

    /**
     * Answers a request whose key already exists.
     *
     * @param stored      the stored state of the key
     * @param fingerprint the content of the request
     * @param replay      how an accepted outcome is answered again
     * @param <T>         the answer of the request
     * @return the replayed answer; it fails with {@code IDEMPOTENCY_KEY_REUSED} for another content,
     * {@code REQUEST_IN_PROGRESS} while another request holds the key, or the stored rejection
     */
    public static <T> Mono<T> repeat(IdempotencyRecord stored, RequestFingerprint fingerprint,
                                     Function<PlacementOutcome.Accepted, Mono<T>> replay) {
        return Mono.just(stored)
                .filter(candidate -> candidate.matches(fingerprint))
                .switchIfEmpty(Mono.defer(() -> Mono.error(IDEMPOTENCY_KEY_REUSED.build())))
                .filter(candidate -> candidate.status() == IdempotencyStatus.COMPLETED)
                .switchIfEmpty(Mono.defer(() -> Mono.error(REQUEST_IN_PROGRESS.build())))
                .flatMap(completed -> switch (completed.outcome()) {
                    case PlacementOutcome.Accepted accepted -> replay.apply(accepted);
                    case PlacementOutcome.Rejected rejected -> Mono.<T>error(rejected.error().build());
                });
    }

    /**
     * Settles the key of a request that holds it when the request fails. A success closes the key in
     * its own atomic write, so it is left untouched.
     *
     * @param work       the request
     * @param repository the store of the keys
     * @param scope      the scope of the key
     * @param key        the key
     * @param lockToken  the token that proves the request holds the key
     * @param <T>        the answer of the request
     * @return the answer of the request, or its error once the key is settled
     */
    public static <T> Mono<T> settleOnFailure(Mono<T> work, IdempotencyRepository repository, IdempotencyScope scope,
                                              IdempotencyKey key, UUID lockToken) {
        return work
                .onErrorResume(IdempotencyGuard::isDefinitiveRejection, error -> repository
                        .complete(scope, key, lockToken,
                                new PlacementOutcome.Rejected(((BusinessException) error).getBusinessErrorMessage()))
                        .then(Mono.error(error)))
                .onErrorResume(error -> !(error instanceof BusinessException), error -> repository
                        .release(scope, key, lockToken)
                        .then(Mono.error(error)));
    }

    private static Boolean isDefinitiveRejection(Throwable error) {
        return error instanceof BusinessException business
                && business.getBusinessErrorMessage() != REQUEST_IN_PROGRESS;
    }
}
