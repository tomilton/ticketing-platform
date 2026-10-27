package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.entity.EventEntity;
import co.com.nequi.ticketing.dynamodb.entity.InventoryShardEntity;
import co.com.nequi.ticketing.dynamodb.mapper.EventEntityMapper;
import co.com.nequi.ticketing.dynamodb.mapper.InventoryShardEntityMapper;
import co.com.nequi.ticketing.dynamodb.pagination.EventPageCursorCodec;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.EventPage;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Events stored in DynamoDB. A new event and its shards are written in one transaction, so either
 * all the items exist or none does.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class EventDynamoRepository implements EventRepository {

    static final TableSchema<EventEntity> EVENT_SCHEMA = TableSchema.fromBean(EventEntity.class);
    static final TableSchema<InventoryShardEntity> SHARD_SCHEMA = TableSchema.fromBean(InventoryShardEntity.class);
    private static final String EVENT_ID = "eventId";
    private static final String NOT_EXISTS_CONDITION = "attribute_not_exists(eventId)";
    private static final String LISTABLE_CONDITION = "#status = :status AND #date > :now";
    private static final Map<String, String> LISTABLE_NAMES = Map.of("#status", "status", "#date", "date");

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final EventEntityMapper eventMapper;
    private final InventoryShardEntityMapper shardMapper;
    private final EventPageCursorCodec cursorCodec;
    private final DynamoDbBatchReader batchReader;

    @Override
    public Mono<Event> create(Event event, List<InventoryShard> shards) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB TransactWriteItems, table={}, eventId={}, shards={}",
                            properties.eventsTable(), event.getEventId().value(), shards.size());
                    return client.transactWriteItems(buildCreateTransaction(event, shards));
                })
                .thenReturn(event)
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<Event> findById(EventId eventId) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB GetItem, table={}, eventId={}", properties.eventsTable(), eventId.value());
                    return client.getItem(GetItemRequest.builder()
                            .tableName(properties.eventsTable())
                            .key(key(eventId))
                            .consistentRead(Boolean.TRUE)
                            .build());
                })
                .retryWhen(DynamoDbRetryPolicy.retrySpec())
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> toEvent(response.item()))
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<EventPage> findListable(Instant now, EventPageQuery query) {
        return Mono.fromCallable(() -> buildListableQuery(now, query))
                .flatMap(request -> Mono.fromFuture(() -> {
                    log.debug("DynamoDB Query, table={}, index={}, limit={}",
                            properties.eventsTable(), properties.eventsByStatusDateIndex(), query.limit());
                    return client.query(request);
                }))
                .retryWhen(DynamoDbRetryPolicy.retrySpec())
                .map(this::toEventPage)
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Flux<Event> findAllById(Set<EventId> eventIds) {
        log.debug("DynamoDB BatchGetItem, table={}, keys={}", properties.eventsTable(), eventIds.size());
        return batchReader.read(properties.eventsTable(), eventIds.stream().map(EventDynamoRepository::key).toList())
                .map(this::toEvent)
                .onErrorMap(this::toTechnicalException);
    }

    private TransactWriteItemsRequest buildCreateTransaction(Event event, List<InventoryShard> shards) {
        Stream<TransactWriteItem> eventPut = Stream.of(conditionalPut(properties.eventsTable(),
                EVENT_SCHEMA.itemToMap(eventMapper.toEntity(event), true)));
        Stream<TransactWriteItem> shardPuts = shards.stream()
                .map(shard -> conditionalPut(properties.inventoryTable(),
                        SHARD_SCHEMA.itemToMap(shardMapper.toEntity(shard), true)));
        return TransactWriteItemsRequest.builder()
                .transactItems(Stream.concat(eventPut, shardPuts).toList())
                .build();
    }

    private static TransactWriteItem conditionalPut(String tableName, Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder().tableName(tableName).item(item).conditionExpression(NOT_EXISTS_CONDITION).build())
                .build();
    }

    private QueryRequest buildListableQuery(Instant now, EventPageQuery query) {
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(properties.eventsTable())
                .indexName(properties.eventsByStatusDateIndex())
                .keyConditionExpression(LISTABLE_CONDITION)
                .expressionAttributeNames(LISTABLE_NAMES)
                .expressionAttributeValues(Map.of(
                        ":status", AttributeValue.fromS(EventStatus.ACTIVE.name()),
                        ":now", AttributeValue.fromN(String.valueOf(now.toEpochMilli()))))
                .scanIndexForward(Boolean.TRUE)
                .limit(query.limit());
        cursorCodec.decode(query.cursor()).ifPresent(request::exclusiveStartKey);
        return request.build();
    }

    private EventPage toEventPage(QueryResponse response) {
        List<Event> events = response.items().stream().map(this::toEvent).toList();
        return new EventPage(events, cursorCodec.encode(response.lastEvaluatedKey()).orElse(null));
    }

    private Event toEvent(Map<String, AttributeValue> item) {
        return eventMapper.toDomain(EVENT_SCHEMA.mapToItem(item));
    }

    private static Map<String, AttributeValue> key(EventId eventId) {
        return Map.of(EVENT_ID, AttributeValue.fromS(eventId.value().toString()));
    }

    private TechnicalException toTechnicalException(Throwable error) {
        return error instanceof TechnicalException technicalException
                ? technicalException
                : DYNAMODB_ERROR.build(error);
    }
}
