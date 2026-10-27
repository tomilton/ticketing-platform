package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.SchedulerLockProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoDbLockRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final UUID INSTANCE_ID = UUID.fromString("9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a");
    private static final String LOCK_NAME = "reservation-sweep";
    private static final Duration LEASE = Duration.ofSeconds(60);

    @Mock
    private DynamoDbAsyncClient client;

    private DynamoDbLockRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DynamoDbLockRepository(client, new SchedulerLockProperties("scheduler_locks"), () -> NOW,
                INSTANCE_ID);
    }

    @Test
    void shouldReturnTrueWithConditionalPutWhenLockIsTaken() {
        // Given
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        when(client.putItem(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.tryAcquire(LOCK_NAME, LEASE))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
        PutItemRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("scheduler_locks");
        assertThat(request.conditionExpression())
                .isEqualTo("attribute_not_exists(lockName) OR lockedUntilEpochMillis < :nowMillis OR #owner = :me");
        assertThat(request.item().get("lockName").s()).isEqualTo(LOCK_NAME);
        assertThat(request.item().get("owner").s()).isEqualTo(INSTANCE_ID.toString());
        assertThat(request.item().get("lockedUntilEpochMillis").n())
                .isEqualTo(String.valueOf(NOW.plus(LEASE).toEpochMilli()));
        assertThat(request.expressionAttributeValues().get(":nowMillis").n())
                .isEqualTo(String.valueOf(NOW.toEpochMilli()));
        assertThat(request.expressionAttributeValues().get(":me").s()).isEqualTo(INSTANCE_ID.toString());
    }

    @Test
    void shouldReturnFalseWhenAnotherReplicaHoldsTheLock() {
        // Given
        when(client.putItem(any(PutItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ConditionalCheckFailedException.builder().message("held").build()));

        // When / Then
        StepVerifier.create(repository.tryAcquire(LOCK_NAME, LEASE))
                .expectNext(Boolean.FALSE)
                .verifyComplete();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenAcquireFails() {
        // Given
        when(client.putItem(any(PutItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                DynamoDbException.builder().message("down").build()));

        // When / Then
        StepVerifier.create(repository.tryAcquire(LOCK_NAME, LEASE))
                .expectErrorSatisfies(DynamoDbLockRepositoryTest::assertDynamoDbError)
                .verify();
    }

    @Test
    void shouldDeleteOnlyOwnLockWhenLockIsReleased() {
        // Given
        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        when(client.deleteItem(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(DeleteItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.release(LOCK_NAME))
                .verifyComplete();
        DeleteItemRequest request = captor.getValue();
        assertThat(request.key().get("lockName").s()).isEqualTo(LOCK_NAME);
        assertThat(request.conditionExpression()).isEqualTo("#owner = :me");
        assertThat(request.expressionAttributeValues().get(":me").s()).isEqualTo(INSTANCE_ID.toString());
    }

    @Test
    void shouldCompleteWhenLockIsNoLongerOwned() {
        // Given
        when(client.deleteItem(any(DeleteItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ConditionalCheckFailedException.builder().message("taken").build()));

        // When / Then
        StepVerifier.create(repository.release(LOCK_NAME))
                .verifyComplete();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenReleaseFails() {
        // Given
        when(client.deleteItem(any(DeleteItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                DynamoDbException.builder().message("down").build()));

        // When / Then
        StepVerifier.create(repository.release(LOCK_NAME))
                .expectErrorSatisfies(DynamoDbLockRepositoryTest::assertDynamoDbError)
                .verify();
    }

    private static void assertDynamoDbError(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }
}
