package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventDynamoRepositoryIntegrationTest {

    private static final Instant FUTURE_DATE = Instant.parse("2027-03-01T20:00:00Z");
    private static final Instant PAST_DATE = Instant.parse("2025-03-01T20:00:00Z");

    private static DynamoDbIntegrationFixture fixture;
    private static EventDynamoRepository eventRepository;
    private static InventoryDynamoRepository inventoryRepository;

    @BeforeAll
    static void setUp() {
        fixture = new DynamoDbIntegrationFixture();
        eventRepository = fixture.eventRepository();
        inventoryRepository = fixture.inventoryRepository();
    }

    @Test
    void shouldStoreEventAndItsShardsWhenEventIsCreated() {
        // Given
        Event event = event(EventId.newId(), 10, 3);

        // When / Then
        StepVerifier.create(create(event).then(eventRepository.findById(event.getEventId())))
                .assertNext(stored -> assertThat(stored).usingRecursiveComparison().isEqualTo(event))
                .verifyComplete();
        StepVerifier.create(inventoryRepository.findShards(event.getEventId()).collectList())
                .assertNext(shards -> assertThat(EventInventory.aggregate(event.getEventId(), shards))
                        .isEqualTo(new EventInventory(event.getEventId(), 10, 10, 0, 0, 0)))
                .verifyComplete();
    }

    @Test
    void shouldFailWithoutWritingShardsWhenEventIdAlreadyExists() {
        // Given
        EventId eventId = EventId.newId();
        Event duplicate = event(eventId, 20, 5);

        // When / Then
        StepVerifier.create(create(event(eventId, 10, 3)).then(create(duplicate)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(TechnicalException.class)
                        .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR))
                .verify();
        StepVerifier.create(inventoryRepository.findShards(eventId).count())
                .expectNext(3L)
                .verifyComplete();
    }

    @Test
    void shouldReturnCancelledAndPastEventsWithTheirStatusAndDateWhenFoundById() {
        // When / Then
        StepVerifier.create(fixture.putEvent(EventStatus.CANCELLED, FUTURE_DATE).flatMap(eventRepository::findById))
                .assertNext(event -> {
                    assertThat(event.getStatus()).isEqualTo(EventStatus.CANCELLED);
                    assertThat(event.getDate()).isEqualTo(FUTURE_DATE);
                })
                .verifyComplete();
        StepVerifier.create(fixture.putEvent(EventStatus.ACTIVE, PAST_DATE).flatMap(eventRepository::findById))
                .assertNext(event -> {
                    assertThat(event.getStatus()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(event.getDate()).isEqualTo(PAST_DATE);
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateTwoEventsWhenSameDataIsSentTwice() {
        // Given
        Event first = event(EventId.newId(), 10, 3);
        Event second = event(EventId.newId(), 10, 3);

        // When / Then
        StepVerifier.create(create(first).then(create(second))
                        .then(Mono.zip(inventoryRepository.findShards(first.getEventId()).count(),
                                inventoryRepository.findShards(second.getEventId()).count())))
                .assertNext(counts -> {
                    assertThat(first.getEventId()).isNotEqualTo(second.getEventId());
                    assertThat(counts.getT1()).isEqualTo(3L);
                    assertThat(counts.getT2()).isEqualTo(3L);
                })
                .verifyComplete();
    }

    private static Mono<Event> create(Event event) {
        return eventRepository.create(event,
                EventInventory.initialShards(event.getEventId(), event.getCapacity(), event.getShardCount()));
    }

    private static Event event(EventId eventId, Integer capacity, Integer shardCount) {
        return Event.create(eventId, "Rock al Parque", new Venue("Parque Simon Bolivar", "Bogota"), FUTURE_DATE,
                new Capacity(capacity), shardCount);
    }
}
