package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoDbBatchReaderTest {

    private static final String TABLE = "events";
    private static final Map<String, AttributeValue> FIRST_KEY = Map.of("eventId", AttributeValue.fromS("a"));
    private static final Map<String, AttributeValue> SECOND_KEY = Map.of("eventId", AttributeValue.fromS("b"));
    private static final Duration ENOUGH_TIME = Duration.ofSeconds(10);

    @Mock
    private DynamoDbAsyncClient client;

    @InjectMocks
    private DynamoDbBatchReader reader;

    @Test
    void shouldReadWithConsistentReadAndOmitMissingKeysWhenBatchIsRead() {
        // Given
        ArgumentCaptor<BatchGetItemRequest> request = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        when(client.batchGetItem(request.capture())).thenReturn(response(List.of(FIRST_KEY), Map.of()));

        // When / Then
        StepVerifier.create(reader.read(TABLE, List.of(FIRST_KEY, SECOND_KEY)))
                .expectNext(FIRST_KEY)
                .verifyComplete();
        KeysAndAttributes sent = request.getValue().requestItems().get(TABLE);
        assertThat(sent.keys()).containsExactly(FIRST_KEY, SECOND_KEY);
        assertThat(sent.consistentRead()).isTrue();
    }

    @Test
    void shouldRetryUnprocessedKeysWhenDynamoDbLeavesKeysPending() {
        // Given
        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(response(List.of(FIRST_KEY), Map.of(TABLE, keys(SECOND_KEY))),
                        response(List.of(SECOND_KEY), Map.of()));

        // When / Then
        StepVerifier.withVirtualTime(() -> reader.read(TABLE, List.of(FIRST_KEY, SECOND_KEY)))
                .expectNext(FIRST_KEY)
                .thenAwait(ENOUGH_TIME)
                .expectNext(SECOND_KEY)
                .verifyComplete();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenKeysRemainUnprocessedAfterThreeRetries() {
        // Given
        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(response(List.of(), Map.of(TABLE, keys(FIRST_KEY))));

        // When / Then
        StepVerifier.withVirtualTime(() -> reader.read(TABLE, List.of(FIRST_KEY)))
                .thenAwait(ENOUGH_TIME)
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(TechnicalException.class)
                        .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR))
                .verify();
        verify(client, times(4)).batchGetItem(any(BatchGetItemRequest.class));
    }

    private static KeysAndAttributes keys(Map<String, AttributeValue> key) {
        return KeysAndAttributes.builder().keys(key).consistentRead(true).build();
    }

    private static CompletableFuture<BatchGetItemResponse> response(List<Map<String, AttributeValue>> items,
                                                                   Map<String, KeysAndAttributes> unprocessed) {
        return CompletableFuture.completedFuture(BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, items))
                .unprocessedKeys(unprocessed)
                .build());
    }
}
