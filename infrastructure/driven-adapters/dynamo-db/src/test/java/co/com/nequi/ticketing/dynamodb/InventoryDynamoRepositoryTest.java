package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.InventoryShardEntityMapper;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryDynamoRepositoryTest {

    private static final String INVENTORY_TABLE = "inventory";
    private static final Integer BATCH_CONCURRENCY = 2;
    private static final Integer MAX_SHARDS = 20;
    private static final Integer KEYS_PER_GROUP = 100;
    private static final EventId EVENT_ID = EventId.newId();

    @Mock
    private DynamoDbAsyncClient client;

    private final InventoryShardEntityMapper shardMapper = Mappers.getMapper(InventoryShardEntityMapper.class);

    private InventoryDynamoRepository repository;

    @BeforeEach
    void setUp() {
        DynamoDbProperties properties = new DynamoDbProperties("us-east-1", null, "events", INVENTORY_TABLE,
                "by-status-date", Duration.ofSeconds(2), Duration.ofMillis(500), BATCH_CONCURRENCY,
                Duration.ofSeconds(1),
                "orders", "order_audit", "idempotency", "customer_event_limits", "expiring-reservations",
                "pending-publication", 10, Duration.ofSeconds(5));
        repository = new InventoryDynamoRepository(client, properties, shardMapper, new DynamoDbBatchReader(client));
    }

    @Test
    void shouldQueryShardsWithConsistentReadWhenShardsOfOneEventAreRead() {
        // Given
        InventoryShard shard = new InventoryShard(EVENT_ID, 0, 10, 7, 3, 0, 0, 2L);
        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        when(client.query(request.capture())).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(item(shard)).build()));

        // When / Then
        StepVerifier.create(repository.findShards(EVENT_ID))
                .expectNext(shard)
                .verifyComplete();
        assertThat(request.getValue().tableName()).isEqualTo(INVENTORY_TABLE);
        assertThat(request.getValue().consistentRead()).isTrue();
        assertThat(request.getValue().expressionAttributeValues())
                .containsEntry(":eventId", AttributeValue.fromS(EVENT_ID.value().toString()));
    }

    @Test
    void shouldReadEveryShardKeyInGroupsOfOneHundredWhenShardsOfSeveralEventsAreRead() {
        // Given
        Event first = event(EventId.newId(), 3);
        Event second = event(EventId.newId(), 1);
        ArgumentCaptor<BatchGetItemRequest> request = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        when(client.batchGetItem(request.capture())).thenReturn(emptyResponse());

        // When / Then
        StepVerifier.create(repository.findShardsOf(List.of(first, second))).verifyComplete();
        KeysAndAttributes keys = request.getValue().requestItems().get(INVENTORY_TABLE);
        assertThat(keys.consistentRead()).isTrue();
        assertThat(keys.keys()).containsExactly(shardKey(first, 0), shardKey(first, 1), shardKey(first, 2),
                shardKey(second, 0));
    }

    @Test
    void shouldKeepAtMostBatchConcurrencyGroupsInFlightWhenThereAreFiveGroups() {
        // Given
        List<Event> events = IntStream.range(0, 25).mapToObj(index -> event(EventId.newId(), MAX_SHARDS)).toList();
        List<CompletableFuture<BatchGetItemResponse>> inFlight = new ArrayList<>();
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenAnswer(invocation -> {
            CompletableFuture<BatchGetItemResponse> future = new CompletableFuture<>();
            inFlight.add(future);
            return future;
        });

        // When / Then
        StepVerifier.create(repository.findShardsOf(events))
                .then(() -> assertThat(inFlight).hasSize(BATCH_CONCURRENCY))
                .then(() -> completeAll(inFlight))
                .then(() -> assertThat(inFlight).hasSize(2 * BATCH_CONCURRENCY))
                .then(() -> completeAll(inFlight))
                .then(() -> assertThat(inFlight).hasSize(events.size() * MAX_SHARDS / KEYS_PER_GROUP))
                .then(() -> completeAll(inFlight))
                .verifyComplete();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenKeysRemainUnprocessed() {
        // Given
        Event event = event(EVENT_ID, 1);
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                BatchGetItemResponse.builder()
                        .responses(Map.of(INVENTORY_TABLE, List.of()))
                        .unprocessedKeys(Map.of(INVENTORY_TABLE, KeysAndAttributes.builder()
                                .keys(shardKey(event, 0)).build()))
                        .build()));

        // When / Then
        StepVerifier.withVirtualTime(() -> repository.findShardsOf(List.of(event)))
                .thenAwait(Duration.ofSeconds(10))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(TechnicalException.class)
                        .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR))
                .verify();
    }

    private static void completeAll(List<CompletableFuture<BatchGetItemResponse>> futures) {
        futures.stream().filter(future -> !future.isDone()).toList()
                .forEach(future -> future.complete(BatchGetItemResponse.builder()
                        .responses(Map.of(INVENTORY_TABLE, List.of())).build()));
    }

    private Map<String, AttributeValue> item(InventoryShard shard) {
        return EventDynamoRepository.SHARD_SCHEMA.itemToMap(shardMapper.toEntity(shard), true);
    }

    private static Map<String, AttributeValue> shardKey(Event event, Integer shardId) {
        return Map.of("eventId", AttributeValue.fromS(event.getEventId().value().toString()),
                "shardId", AttributeValue.fromN(shardId.toString()));
    }

    private static CompletableFuture<BatchGetItemResponse> emptyResponse() {
        return CompletableFuture.completedFuture(BatchGetItemResponse.builder()
                .responses(Map.of(INVENTORY_TABLE, List.of())).build());
    }

    private static Event event(EventId eventId, Integer shardCount) {
        return Event.create(eventId, "Rock al Parque", new Venue("Parque Simon Bolivar", "Bogota"),
                Instant.parse("2026-12-01T20:00:00Z"), new Capacity(MAX_SHARDS), shardCount);
    }
}
