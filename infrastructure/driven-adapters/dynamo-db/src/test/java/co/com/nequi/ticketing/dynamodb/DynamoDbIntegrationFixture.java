package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.entity.EventEntity;
import co.com.nequi.ticketing.dynamodb.mapper.EventEntityMapper;
import co.com.nequi.ticketing.dynamodb.mapper.InventoryShardEntityMapper;
import co.com.nequi.ticketing.dynamodb.pagination.EventPageCursorCodec;
import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.shared.EventId;
import org.mapstruct.factory.Mappers;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Repositories wired against the LocalStack container, and direct writes for data that the API
 * cannot create.
 */
final class DynamoDbIntegrationFixture {

    private static final InventoryShardEntityMapper SHARD_MAPPER = Mappers.getMapper(InventoryShardEntityMapper.class);

    private final DynamoDbAsyncClient client = LocalStackTestSupport.asyncClient();
    private final DynamoDbProperties properties = LocalStackTestSupport.properties();
    private final DynamoDbBatchReader batchReader = new DynamoDbBatchReader(client);

    EventDynamoRepository eventRepository() {
        return new EventDynamoRepository(client, properties, Mappers.getMapper(EventEntityMapper.class), SHARD_MAPPER,
                new EventPageCursorCodec(JsonMapper.builder().build()), batchReader);
    }

    InventoryDynamoRepository inventoryRepository() {
        return new InventoryDynamoRepository(client, properties, SHARD_MAPPER, batchReader);
    }

    Mono<EventId> putEvent(EventStatus status, Instant date) {
        EventId eventId = EventId.newId();
        EventEntity entity = new EventEntity();
        entity.setEventId(eventId.value().toString());
        entity.setName("Stored event");
        entity.setVenueName("Movistar Arena");
        entity.setCity("Bogota");
        entity.setDate(date.toEpochMilli());
        entity.setCapacity(1);
        entity.setStatus(status);
        entity.setShardCount(1);
        entity.setVersion(1L);
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(properties.eventsTable())
                        .item(EventDynamoRepository.EVENT_SCHEMA.itemToMap(entity, true))
                        .build()))
                .thenReturn(eventId);
    }
}
