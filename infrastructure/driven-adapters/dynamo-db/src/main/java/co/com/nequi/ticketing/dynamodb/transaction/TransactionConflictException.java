package co.com.nequi.ticketing.dynamodb.transaction;

/**
 * Signal that DynamoDB cancelled a transaction because another one was writing the same items. The
 * result is certain, so the transaction is retried. It never leaves the adapter.
 */
public class TransactionConflictException extends RuntimeException {

    public TransactionConflictException(Throwable cause) {
        super("Transaction cancelled by a conflict", cause);
    }
}
