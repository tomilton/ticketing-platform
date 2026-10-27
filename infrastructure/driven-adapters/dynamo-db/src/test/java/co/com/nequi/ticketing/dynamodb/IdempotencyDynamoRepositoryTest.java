package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper;
import co.com.nequi.ticketing.dynamodb.support.OrderFixtures;
import co.com.nequi.ticketing.model.config.IdempotencySettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.SCOPE;
import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.KEY;
import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.LOCK_TOKEN;
import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyDynamoRepositoryTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final RequestFingerprint FINGERPRINT = RequestFingerprint.of(EventId.newId(), new Quantity(2));

    @Mock
    private DynamoDbAsyncClient client;

    private final IdempotencyEntityMapper mapper = new IdempotencyEntityMapper();

    private IdempotencyDynamoRepository repository;

    @BeforeEach
    void setUp() {
        repository = new IdempotencyDynamoRepository(client, OrderFixtures.properties(10, Duration.ofSeconds(5)), mapper,
                new IdempotencySettings(TTL, LEASE));
    }

    @Test
    void shouldGrantKeyWithLeaseInMillisecondsAndTtlInSecondsWhenKeyIsNew() {
        // Given
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        when(client.putItem(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.claim(SCOPE, KEY, FINGERPRINT, NOW))
                .assertNext(claim -> assertThat(claim).isInstanceOfSatisfying(IdempotencyClaim.Granted.class,
                        granted -> assertThat(captor.getValue().item().get("lockToken").s())
                                .isEqualTo(granted.lockToken().toString())))
                .verifyComplete();
        PutItemRequest request = captor.getValue();
        assertThat(request.conditionExpression())
                .isEqualTo("attribute_not_exists(idempotencyId) OR expiresAtEpochSeconds < :nowSeconds");
        assertThat(request.expressionAttributeValues())
                .isEqualTo(Map.of(":nowSeconds", AttributeValue.fromN(String.valueOf(NOW.getEpochSecond()))));
        assertThat(request.item())
                .containsEntry("status", AttributeValue.fromS("IN_PROGRESS"))
                .containsEntry("requestHash", AttributeValue.fromS(FINGERPRINT.value()))
                .containsEntry("lockedUntilEpochMillis", AttributeValue.fromN(String.valueOf(NOW.plus(LEASE).toEpochMilli())))
                .containsEntry("expiresAtEpochSeconds", AttributeValue.fromN(String.valueOf(NOW.plus(TTL).getEpochSecond())));
    }

    @Test
    void shouldReturnExistingRecordWhenKeyIsTakenAndLeaseIsActive() {
        // Given
        when(client.putItem(any(PutItemRequest.class))).thenReturn(conditionFailed());
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(stored(IdempotencyStatus.IN_PROGRESS, NOW.plusSeconds(5)));

        // When / Then
        StepVerifier.create(repository.claim(SCOPE, KEY, FINGERPRINT, NOW))
                .assertNext(claim -> assertThat(claim).isInstanceOfSatisfying(IdempotencyClaim.Existing.class,
                        existing -> assertThat(existing.record().lockToken()).isEqualTo(LOCK_TOKEN)))
                .verifyComplete();
    }

    @Test
    void shouldReturnExistingCompletedRecordWhenKeyWasClosed() {
        // Given
        when(client.putItem(any(PutItemRequest.class))).thenReturn(conditionFailed());
        when(client.getItem(any(GetItemRequest.class))).thenReturn(stored(IdempotencyStatus.COMPLETED, NOW.minusSeconds(5)));

        // When / Then
        StepVerifier.create(repository.claim(SCOPE, KEY, FINGERPRINT, NOW))
                .assertNext(claim -> assertThat(claim).isInstanceOf(IdempotencyClaim.Existing.class))
                .verifyComplete();
    }

    @Test
    void shouldClaimAgainOnPreviousTokenWhenLeaseEnded() {
        // Given
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        when(client.putItem(captor.capture()))
                .thenReturn(conditionFailed())
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(stored(IdempotencyStatus.IN_PROGRESS, NOW));

        // When / Then
        StepVerifier.create(repository.claim(SCOPE, KEY, FINGERPRINT, NOW))
                .assertNext(claim -> assertThat(claim).isInstanceOf(IdempotencyClaim.Granted.class))
                .verifyComplete();
        PutItemRequest reclaim = captor.getAllValues().get(1);
        assertThat(reclaim.conditionExpression()).isEqualTo("#status = :inProgress AND lockToken = :previousToken");
        assertThat(reclaim.expressionAttributeValues().get(":previousToken"))
                .isEqualTo(AttributeValue.fromS(LOCK_TOKEN.toString()));
    }

    @Test
    void shouldReturnRecordOfWinnerWhenAnotherRequestReclaimedFirst() {
        // Given
        when(client.putItem(any(PutItemRequest.class))).thenReturn(conditionFailed()).thenReturn(conditionFailed());
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(stored(IdempotencyStatus.IN_PROGRESS, NOW))
                .thenReturn(stored(IdempotencyStatus.IN_PROGRESS, NOW.plusSeconds(10)));

        // When / Then
        StepVerifier.create(repository.claim(SCOPE, KEY, FINGERPRINT, NOW))
                .assertNext(claim -> assertThat(claim).isInstanceOf(IdempotencyClaim.Existing.class))
                .verifyComplete();
    }

    @Test
    void shouldCloseKeyOnlyWhileHeldWhenOutcomeIsStored() {
        // Given
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        when(client.updateItem(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.complete(SCOPE, KEY, LOCK_TOKEN,
                        new PlacementOutcome.Rejected(BusinessErrorMessage.TICKETS_SOLD_OUT)))
                .verifyComplete();
        UpdateItemRequest request = captor.getValue();
        assertThat(request.conditionExpression()).isEqualTo("#status = :inProgress AND lockToken = :token");
        assertThat(request.updateExpression()).isEqualTo("SET #status = :completed, errorCode = :errorCode, "
                + "outcomeType = :outcomeType");
        assertThat(request.expressionAttributeValues())
                .containsEntry(":errorCode", AttributeValue.fromS("TKB0003"))
                .containsEntry(":token", AttributeValue.fromS(LOCK_TOKEN.toString()));
    }

    @Test
    void shouldIgnoreLostKeyWhenOutcomeCannotBeStored() {
        // Given
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(conditionFailed());

        // When / Then
        StepVerifier.create(repository.complete(SCOPE, KEY, LOCK_TOKEN,
                        new PlacementOutcome.Rejected(BusinessErrorMessage.TICKETS_SOLD_OUT)))
                .verifyComplete();
    }

    @Test
    void shouldDeleteKeyOnlyWhileHeldWhenKeyIsReleased() {
        // Given
        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        when(client.deleteItem(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(DeleteItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.release(SCOPE, KEY, LOCK_TOKEN))
                .verifyComplete();
        assertThat(captor.getValue().conditionExpression()).isEqualTo("#status = :inProgress AND lockToken = :token");
    }

    @Test
    void shouldIgnoreReleaseWithoutDeletingWhenKeyIsAlreadyCompleted() {
        // Given
        when(client.deleteItem(any(DeleteItemRequest.class))).thenReturn(conditionFailed());

        // When / Then
        StepVerifier.create(repository.release(SCOPE, KEY, LOCK_TOKEN))
                .verifyComplete();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenReleaseFailsTechnically() {
        // Given
        when(client.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database down")));

        // When / Then
        StepVerifier.create(repository.release(SCOPE, KEY, LOCK_TOKEN))
                .expectError(TechnicalException.class)
                .verify();
    }

    private static <T> CompletableFuture<T> conditionFailed() {
        return CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().message("taken").build());
    }

    private CompletableFuture<GetItemResponse> stored(IdempotencyStatus status, Instant lockedUntil) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("idempotencyId", AttributeValue.fromS(mapper.idempotencyId(SCOPE, KEY)));
        item.put("requestHash", AttributeValue.fromS(FINGERPRINT.value()));
        item.put("status", AttributeValue.fromS(status.name()));
        item.put("lockToken", AttributeValue.fromS(LOCK_TOKEN.toString()));
        item.put("lockedUntilEpochMillis", AttributeValue.fromN(String.valueOf(lockedUntil.toEpochMilli())));
        if (status == IdempotencyStatus.COMPLETED) {
            item.putAll(mapper.outcomeAttributes(new PlacementOutcome.Rejected(BusinessErrorMessage.TICKETS_SOLD_OUT)));
        }
        return CompletableFuture.completedFuture(GetItemResponse.builder().item(item).build());
    }
}
