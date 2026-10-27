package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.EventEntityMapper;
import co.com.nequi.ticketing.dynamodb.mapper.InventoryShardEntityMapper;
import co.com.nequi.ticketing.dynamodb.pagination.EventPageCursorCodec;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import co.com.nequi.ticketing.model.inventory.EventInventory;
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
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventDynamoRepositoryTest {

    private static final String EVENTS_TABLE = "events";
    private static final String INVENTORY_TABLE = "inventory";
    private static final String INDEX = "by-status-date";
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final EventId EVENT_ID = new EventId(UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f"));
    private static final Event EVENT = Event.create(EVENT_ID, "Rock al Parque",
            new Venue("Parque Simon Bolivar", "Bogota"), Instant.parse("2026-12-01T20:00:00Z"), new Capacity(10), 3);

    @Mock
    private DynamoDbAsyncClient client;

    private final EventEntityMapper eventMapper = Mappers.getMapper(EventEntityMapper.class);
    private final EventPageCursorCodec cursorCodec = new EventPageCursorCodec(JsonMapper.builder().build());

    private EventDynamoRepository repository;

    @BeforeEach
    void setUp() {
        DynamoDbProperties properties = new DynamoDbProperties("us-east-1", null, EVENTS_TABLE, INVENTORY_TABLE,
                INDEX, Duration.ofSeconds(2), Duration.ofMillis(500), 2, Duration.ofSeconds(1),
                "orders", "order_audit", "idempotency", "customer_event_limits", "expiring-reservations",
                "pending-publication", 10, Duration.ofSeconds(5));
        repository = new EventDynamoRepository(client, properties, eventMapper,
                Mappers.getMapper(InventoryShardEntityMapper.class), cursorCodec, new DynamoDbBatchReader(client));
    }

    @Test
    void shouldWriteEventAndShardsInOneConditionalTransactionWhenEventIsCreated() {
        // Given
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        when(client.transactWriteItems(request.capture()))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.create(EVENT, EventInventory.initialShards(EVENT_ID, new Capacity(10), 3)))
                .expectNext(EVENT)
                .verifyComplete();
        List<TransactWriteItem> items = request.getValue().transactItems();
        assertThat(items).hasSize(4)
                .allSatisfy(item -> assertThat(item.put().conditionExpression()).isEqualTo("attribute_not_exists(eventId)"));
        assertThat(items.getFirst().put().tableName()).isEqualTo(EVENTS_TABLE);
        assertThat(items.subList(1, 4)).allSatisfy(item -> assertThat(item.put().tableName()).isEqualTo(INVENTORY_TABLE));
    }

    @Test
    void shouldNotRetryTransactionWhenCreateFails() {
        // Given
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(throttling()));

        // When / Then
        StepVerifier.create(repository.create(EVENT, EventInventory.initialShards(EVENT_ID, new Capacity(10), 3)))
                .expectErrorSatisfies(EventDynamoRepositoryTest::assertDynamoDbError)
                .verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void shouldReturnEmptyWhenEventDoesNotExist() {
        // Given
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.findById(EVENT_ID)).verifyComplete();
    }

    @Test
    void shouldReturnEventWhenItemExists() {
        // Given
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().item(item(EVENT)).build()));

        // When / Then
        StepVerifier.create(repository.findById(EVENT_ID))
                .assertNext(event -> assertThat(event).usingRecursiveComparison().isEqualTo(EVENT))
                .verifyComplete();
    }

    @Test
    void shouldMapSdkErrorToDynamoDbErrorWhenReadFails() {
        // Given
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkException.create("connection reset", null)));

        // When / Then
        StepVerifier.create(repository.findById(EVENT_ID))
                .expectErrorSatisfies(EventDynamoRepositoryTest::assertDynamoDbError)
                .verify();
    }

    @Test
    void shouldQueryActiveEventsAfterNowFromCursorWhenListingEvents() {
        // Given
        Map<String, AttributeValue> lastKey = Map.of("eventId", AttributeValue.fromS(EVENT_ID.value().toString()),
                "status", AttributeValue.fromS("ACTIVE"), "date", AttributeValue.fromN("1796155200000"));
        String cursor = cursorCodec.encode(lastKey).orElseThrow();
        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        when(client.query(request.capture())).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(item(EVENT)).lastEvaluatedKey(lastKey).build()));

        // When / Then
        StepVerifier.create(repository.findListable(NOW, new EventPageQuery(7, cursor)))
                .assertNext(page -> {
                    assertThat(page.items()).singleElement().usingRecursiveComparison().isEqualTo(EVENT);
                    assertThat(page.nextCursor()).isEqualTo(cursor);
                })
                .verifyComplete();
        QueryRequest sent = request.getValue();
        assertThat(sent.indexName()).isEqualTo(INDEX);
        assertThat(sent.keyConditionExpression()).isEqualTo("#status = :status AND #date > :now");
        assertThat(sent.expressionAttributeValues()).containsEntry(":status", AttributeValue.fromS("ACTIVE"))
                .containsEntry(":now", AttributeValue.fromN(String.valueOf(NOW.toEpochMilli())));
        assertThat(sent.scanIndexForward()).isTrue();
        assertThat(sent.limit()).isEqualTo(7);
        assertThat(sent.exclusiveStartKey()).isEqualTo(lastKey);
    }

    @Test
    void shouldReturnNoNextCursorWhenLastPageIsRead() {
        // Given
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(List.of()).build()));

        // When / Then
        StepVerifier.create(repository.findListable(NOW, new EventPageQuery(7, null)))
                .assertNext(page -> {
                    assertThat(page.items()).isEmpty();
                    assertThat(page.nextCursor()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void shouldKeepValidationErrorWhenCursorIsInvalid() {
        // When / Then
        StepVerifier.create(repository.findListable(NOW, new EventPageQuery(7, "bm90LWpzb24")))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(TechnicalException.class)
                        .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.VALIDATION_ERROR))
                .verify();
    }

    @Test
    void shouldReturnOnlyExistingEventsWhenSeveralIdsAreRead() {
        // Given
        when(client.batchGetItem(any(BatchGetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                BatchGetItemResponse.builder().responses(Map.of(EVENTS_TABLE, List.of(item(EVENT)))).build()));

        // When / Then
        StepVerifier.create(repository.findAllById(Set.of(EVENT_ID, EventId.newId())))
                .assertNext(event -> assertThat(event).usingRecursiveComparison().isEqualTo(EVENT))
                .verifyComplete();
    }

    private Map<String, AttributeValue> item(Event event) {
        return EventDynamoRepository.EVENT_SCHEMA.itemToMap(eventMapper.toEntity(event), true);
    }

    private static DynamoDbException throttling() {
        return (DynamoDbException) DynamoDbException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build()).build();
    }

    private static void assertDynamoDbError(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }
}
