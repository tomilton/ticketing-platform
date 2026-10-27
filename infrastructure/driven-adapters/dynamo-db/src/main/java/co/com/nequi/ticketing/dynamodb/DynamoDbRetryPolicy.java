package co.com.nequi.ticketing.dynamodb;

import lombok.experimental.UtilityClass;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkServiceException;

import java.time.Duration;

/**
 * Retry policy of the DynamoDB calls. Only throttling, timeouts and server errors are retried;
 * a failed condition or any other client error never is.
 */
@UtilityClass
public class DynamoDbRetryPolicy {

    private static final long MAX_RETRIES = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(100);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);
    private static final double JITTER = 0.5;
    private static final int FIRST_SERVER_ERROR = 500;

    public static RetryBackoffSpec retrySpec() {
        return Retry.backoff(MAX_RETRIES, FIRST_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .jitter(JITTER)
                .filter(DynamoDbRetryPolicy::isRetryable);
    }

    public static boolean isRetryable(Throwable error) {
        return error instanceof ApiCallAttemptTimeoutException
                || error instanceof ApiCallTimeoutException
                || error instanceof SdkServiceException serviceError
                && (serviceError.isThrottlingException() || serviceError.statusCode() >= FIRST_SERVER_ERROR);
    }
}
