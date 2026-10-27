package co.com.nequi.ticketing.dynamodb.transaction;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.List;
import java.util.stream.IntStream;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;
import static co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder.EVENT_ITEM;
import static co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder.KEY_ITEM;
import static co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder.LIMIT_ITEM;
import static co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder.SHARD_ITEM;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_ACTIVE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.INVALID_STATE_TRANSITION;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.REQUEST_IN_PROGRESS;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKET_LIMIT_EXCEEDED;

/**
 * Translates a cancelled reservation into its cause. Several items can fail at once, so the
 * priority is fixed: the event, the limit of the customer, the shard alone, the idempotency key,
 * a transaction conflict and, last, a technical error. This is the only place where a DynamoDB
 * error becomes a business answer.
 */
@Component
public class TransactionFailureTranslator {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    static final String TRANSACTION_CONFLICT = "TransactionConflict";
    private static final String NONE = "None";

    /**
     * Translates the cancellation of a reservation.
     *
     * @param cancellation the cancellation returned by DynamoDB
     * @return a business exception, {@link ShardSoldOutException} to try another shard,
     * {@link TransactionConflictException} to retry, or a technical exception
     */
    public RuntimeException translateReservation(TransactionCanceledException cancellation) {
        List<String> codes = codes(cancellation);
        if (failed(codes, EVENT_ITEM)) {
            return EVENT_NOT_ACTIVE.build();
        }
        if (failed(codes, LIMIT_ITEM)) {
            return TICKET_LIMIT_EXCEEDED.build();
        }
        if (failed(codes, SHARD_ITEM) && onlyOneFailed(codes)) {
            return new ShardSoldOutException();
        }
        if (failed(codes, KEY_ITEM)) {
            return REQUEST_IN_PROGRESS.build();
        }
        return toConflictOrTechnical(codes, cancellation);
    }

    /**
     * Translates the cancellation of a complimentary grant. Its event and shard items have the same
     * positions as in a reservation.
     *
     * @param cancellation the cancellation returned by DynamoDB
     * @return {@code EVENT_NOT_ACTIVE}, {@link ShardSoldOutException} to try another shard,
     * {@link TransactionConflictException} to retry, or a technical exception
     */
    public RuntimeException translateGrant(TransactionCanceledException cancellation) {
        List<String> codes = codes(cancellation);
        if (failed(codes, EVENT_ITEM)) {
            return EVENT_NOT_ACTIVE.build();
        }
        return failed(codes, SHARD_ITEM) && onlyOneFailed(codes)
                ? new ShardSoldOutException()
                : toConflictOrTechnical(codes, cancellation);
    }

    /**
     * Translates a cancelled change of status. Only the condition of the order is a business answer:
     * another copy changed it first. A failed defensive condition of the inventory is a data error.
     *
     * @param cancellation the cancellation returned by DynamoDB
     * @return {@code INVALID_STATE_TRANSITION}, {@link TransactionConflictException} to retry, or a
     * technical exception
     */
    public RuntimeException translateTransition(TransactionCanceledException cancellation) {
        List<String> codes = codes(cancellation);
        return failed(codes, TransitionTransactionBuilder.ORDER_ITEM)
                ? INVALID_STATE_TRANSITION.build()
                : toConflictOrTechnical(codes, cancellation);
    }

    /**
     * Translates a cancellation that is not caused by a business condition.
     *
     * @param codes        the reason codes in the order of the items
     * @param cancellation the cancellation returned by DynamoDB
     * @return {@link TransactionConflictException} to retry, or a technical exception
     */
    RuntimeException toConflictOrTechnical(List<String> codes, TransactionCanceledException cancellation) {
        return codes.contains(TRANSACTION_CONFLICT)
                ? new TransactionConflictException(cancellation)
                : DYNAMODB_ERROR.build(cancellation);
    }

    static List<String> codes(TransactionCanceledException cancellation) {
        return cancellation.hasCancellationReasons()
                ? cancellation.cancellationReasons().stream().map(CancellationReason::code).toList()
                : List.of();
    }

    static boolean failed(List<String> codes, int item) {
        return item < codes.size() && CONDITIONAL_CHECK_FAILED.equals(codes.get(item));
    }

    private static boolean onlyOneFailed(List<String> codes) {
        return IntStream.range(0, codes.size()).filter(item -> isFailure(codes.get(item))).count() == 1;
    }

    private static boolean isFailure(String code) {
        return code != null && !NONE.equals(code);
    }
}
