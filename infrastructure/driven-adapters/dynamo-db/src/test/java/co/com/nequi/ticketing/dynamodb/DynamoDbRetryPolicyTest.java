package co.com.nequi.ticketing.dynamodb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbRetryPolicyTest {

    private static final Duration ENOUGH_TIME = Duration.ofSeconds(10);
    private static final int MAX_RETRIES = 3;

    @ParameterizedTest
    @MethodSource("retryableErrors")
    void shouldRetryUpToThreeTimesWhenErrorIsRetryable(Exception error) {
        // Given
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> attempts.incrementAndGet() <= MAX_RETRIES
                ? Mono.<String>error(error) : Mono.just("ok"));

        // When / Then
        StepVerifier.withVirtualTime(() -> call.retryWhen(DynamoDbRetryPolicy.retrySpec()))
                .thenAwait(ENOUGH_TIME)
                .expectNext("ok")
                .verifyComplete();
        assertThat(attempts).hasValue(MAX_RETRIES + 1);
    }

    @ParameterizedTest
    @MethodSource("retryableErrors")
    void shouldFailWhenRetryableErrorPersists(Exception error) {
        // Given
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(error);
        });

        // When / Then
        StepVerifier.withVirtualTime(() -> call.retryWhen(DynamoDbRetryPolicy.retrySpec()))
                .thenAwait(ENOUGH_TIME)
                .expectErrorSatisfies(failure -> assertThat(failure).hasCause(error))
                .verify();
        assertThat(attempts).hasValue(MAX_RETRIES + 1);
    }

    @ParameterizedTest
    @MethodSource("nonRetryableErrors")
    void shouldNotRetryWhenErrorIsNotRetryable(Exception error) {
        // Given
        AtomicInteger attempts = new AtomicInteger();
        Mono<String> call = Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(error);
        });

        // When / Then
        StepVerifier.withVirtualTime(() -> call.retryWhen(DynamoDbRetryPolicy.retrySpec()))
                .expectErrorMatches(failure -> failure == error)
                .verify();
        assertThat(attempts).hasValue(1);
    }

    private static Stream<Arguments> retryableErrors() {
        return Stream.of(
                Arguments.of(DynamoDbException.builder().statusCode(400)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build()).build()),
                Arguments.of(ApiCallAttemptTimeoutException.create(1000L)),
                Arguments.of(DynamoDbException.builder().statusCode(500).build()));
    }

    private static Stream<Arguments> nonRetryableErrors() {
        return Stream.of(
                Arguments.of(ConditionalCheckFailedException.builder().statusCode(400).build()),
                Arguments.of(DynamoDbException.builder().statusCode(400).build()),
                Arguments.of(new IllegalStateException("not an SDK error")));
    }
}
