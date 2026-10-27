package co.com.nequi.ticketing.dynamodb.support;

import co.com.nequi.ticketing.dynamodb.DynamoDbBatchReader;
import co.com.nequi.ticketing.dynamodb.EventDynamoRepository;
import co.com.nequi.ticketing.dynamodb.IdempotencyDynamoRepository;
import co.com.nequi.ticketing.dynamodb.InventoryDynamoRepository;
import co.com.nequi.ticketing.dynamodb.OrderDynamoRepository;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.EventEntityMapperImpl;
import co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper;
import co.com.nequi.ticketing.dynamodb.mapper.InventoryShardEntityMapperImpl;
import co.com.nequi.ticketing.dynamodb.mapper.OrderEntityMapperImpl;
import co.com.nequi.ticketing.dynamodb.pagination.EventPageCursorCodec;
import co.com.nequi.ticketing.dynamodb.transaction.AuditItemBuilder;
import co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder;
import co.com.nequi.ticketing.dynamodb.transaction.TransactionFailureTranslator;
import co.com.nequi.ticketing.dynamodb.transaction.TransitionTransactionBuilder;
import co.com.nequi.ticketing.model.config.IdempotencySettings;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * The DynamoDB repositories wired against the LocalStack container, without a Spring context, and
 * helpers to create the events of the integration tests.
 */
public final class LocalStackRepositories {

    private static final Instant FUTURE_DATE = Instant.parse("2030-03-20T01:00:00Z");

    private final DynamoDbAsyncClient client = LocalStackTestSupport.asyncClient();
    private final DynamoDbProperties properties = LocalStackTestSupport.properties();
    private final OrderEntityMapperImpl orderMapper = new OrderEntityMapperImpl();
    private final InventoryShardEntityMapperImpl shardMapper = new InventoryShardEntityMapperImpl();
    private final DynamoDbBatchReader batchReader = new DynamoDbBatchReader(client);
    private final AuditItemBuilder auditItemBuilder = new AuditItemBuilder(properties);

    public DynamoDbAsyncClient client() {
        return client;
    }

    public EventDynamoRepository eventRepository() {
        return new EventDynamoRepository(client, properties, new EventEntityMapperImpl(), shardMapper,
                new EventPageCursorCodec(JsonMapper.builder().build()), batchReader);
    }

    public InventoryDynamoRepository inventoryRepository() {
        return new InventoryDynamoRepository(client, properties, shardMapper, batchReader);
    }

    public OrderDynamoRepository orderRepository() {
        return new OrderDynamoRepository(client, properties, orderMapper,
                new ReservationTransactionBuilder(properties, orderMapper, new IdempotencyEntityMapper(), auditItemBuilder),
                new TransitionTransactionBuilder(properties, auditItemBuilder), new TransactionFailureTranslator());
    }

    public IdempotencyDynamoRepository idempotencyRepository(Duration lease) {
        return new IdempotencyDynamoRepository(client, properties, new IdempotencyEntityMapper(),
                new IdempotencySettings(Duration.ofHours(24), lease));
    }

    /**
     * Creates an active event with a future date and its shards.
     *
     * @param capacity   the tickets of the event
     * @param shardCount the shards of the inventory
     * @return the stored event
     */
    public Mono<Event> createEvent(Integer capacity, Integer shardCount) {
        Event event = Event.create(EventId.newId(), "Integration event", new Venue("Movistar Arena", "Bogota"),
                FUTURE_DATE, new Capacity(capacity), shardCount);
        return eventRepository().create(event,
                EventInventory.initialShards(event.getEventId(), event.getCapacity(), event.getShardCount()));
    }
}
