package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryDynamoRepositoryIntegrationTest {

    private static EventDynamoRepository eventRepository;
    private static InventoryDynamoRepository inventoryRepository;

    @BeforeAll
    static void setUp() {
        DynamoDbIntegrationFixture fixture = new DynamoDbIntegrationFixture();
        eventRepository = fixture.eventRepository();
        inventoryRepository = fixture.inventoryRepository();
    }

    @Test
    void shouldReturnAllShardsAddingUpToCapacityWhenEventHasMaximumShards() {
        // Given
        Event event = event(Capacity.MAX, 20);

        // When / Then
        StepVerifier.create(create(event).thenMany(inventoryRepository.findShards(event.getEventId())).collectList())
                .assertNext(shards -> {
                    assertThat(shards).hasSize(20);
                    assertThat(EventInventory.aggregate(event.getEventId(), shards))
                            .isEqualTo(new EventInventory(event.getEventId(), Capacity.MAX, Capacity.MAX, 0, 0, 0));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnShardsOfExistingEventsOnlyWhenOneIdDoesNotExist() {
        // Given
        List<Event> events = List.of(event(10, 1), event(10, 2), event(10, 3));
        Set<EventId> ids = Set.of(events.get(0).getEventId(), events.get(1).getEventId(), events.get(2).getEventId(),
                EventId.newId());

        // When
        Mono<List<InventoryShard>> shards = Flux.fromIterable(events).concatMap(this::create)
                .thenMany(eventRepository.findAllById(ids))
                .collectList()
                .flatMap(found -> inventoryRepository.findShardsOf(found).collectList());

        // Then
        StepVerifier.create(shards)
                .assertNext(found -> {
                    assertThat(found).hasSize(6);
                    assertThat(found).extracting(InventoryShard::eventId).containsOnly(
                            events.get(0).getEventId(), events.get(1).getEventId(), events.get(2).getEventId());
                })
                .verifyComplete();
    }

    private Mono<Event> create(Event event) {
        return eventRepository.create(event,
                EventInventory.initialShards(event.getEventId(), event.getCapacity(), event.getShardCount()));
    }

    private static Event event(Integer capacity, Integer shardCount) {
        return Event.create(EventId.newId(), "Rock al Parque", new Venue("Parque Simon Bolivar", "Bogota"),
                Instant.parse("2027-03-01T20:00:00Z"), new Capacity(capacity), shardCount);
    }
}
