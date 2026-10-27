package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.InventoryShardEntityMapper;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.inventory.gateways.InventoryRepository;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Inventory shards stored in DynamoDB. Every read is strongly consistent so the availability is
 * never overestimated.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class InventoryDynamoRepository implements InventoryRepository {

    private static final int KEYS_PER_BATCH = 100;
    private static final String EVENT_ID = "eventId";
    private static final String SHARD_ID = "shardId";
    private static final String SHARDS_OF_EVENT_CONDITION = "eventId = :eventId";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final InventoryShardEntityMapper shardMapper;
    private final DynamoDbBatchReader batchReader;

    @Override
    public Flux<InventoryShard> findShards(EventId eventId) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB Query, table={}, eventId={}", properties.inventoryTable(), eventId.value());
                    return client.query(QueryRequest.builder()
                            .tableName(properties.inventoryTable())
                            .keyConditionExpression(SHARDS_OF_EVENT_CONDITION)
                            .expressionAttributeValues(Map.of(":eventId", AttributeValue.fromS(eventId.value().toString())))
                            .consistentRead(Boolean.TRUE)
                            .build());
                })
                .retryWhen(DynamoDbRetryPolicy.retrySpec())
                .flatMapIterable(QueryResponse::items)
                .map(this::toShard)
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Flux<InventoryShard> findShardsOf(List<Event> events) {
        log.debug("DynamoDB BatchGetItem, table={}, events={}", properties.inventoryTable(), events.size());
        return Flux.fromIterable(events)
                .flatMapIterable(InventoryDynamoRepository::shardKeys)
                .buffer(KEYS_PER_BATCH)
                .flatMap(keys -> batchReader.read(properties.inventoryTable(), keys), properties.batchConcurrency())
                .map(this::toShard)
                .onErrorMap(this::toTechnicalException);
    }

    private static List<Map<String, AttributeValue>> shardKeys(Event event) {
        AttributeValue eventId = AttributeValue.fromS(event.getEventId().value().toString());
        return IntStream.range(0, event.getShardCount())
                .mapToObj(shardId -> Map.of(EVENT_ID, eventId, SHARD_ID, AttributeValue.fromN(String.valueOf(shardId))))
                .toList();
    }

    private InventoryShard toShard(Map<String, AttributeValue> item) {
        return shardMapper.toDomain(EventDynamoRepository.SHARD_SCHEMA.mapToItem(item));
    }

    private TechnicalException toTechnicalException(Throwable error) {
        return error instanceof TechnicalException technicalException
                ? technicalException
                : DYNAMODB_ERROR.build(error);
    }
}
