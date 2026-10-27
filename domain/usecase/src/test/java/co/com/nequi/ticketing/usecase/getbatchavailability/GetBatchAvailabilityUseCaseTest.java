package co.com.nequi.ticketing.usecase.getbatchavailability;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.inventory.gateways.InventoryRepository;
import co.com.nequi.ticketing.model.inventory.request.BatchAvailabilityQuery;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetBatchAvailabilityUseCaseTest {

    private static final EventId FIRST_ID = EventId.newId();
    private static final EventId MISSING_ID = EventId.newId();
    private static final EventId SECOND_ID = EventId.newId();

    @Mock
    private EventRepository eventRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Captor
    private ArgumentCaptor<Set<EventId>> eventIdsCaptor;

    @Captor
    private ArgumentCaptor<List<Event>> eventsCaptor;

    @InjectMocks
    private GetBatchAvailabilityUseCase useCase;

    @Test
    void shouldReturnExistingEventsInRequestOrderWhenIdsAreRepeatedOrMissing() {
        // Given
        Event first = event(FIRST_ID);
        Event second = event(SECOND_ID);
        when(eventRepository.findAllById(eventIdsCaptor.capture())).thenReturn(Flux.just(second, first));
        when(inventoryRepository.findShardsOf(eventsCaptor.capture())).thenReturn(Flux.just(
                new InventoryShard(SECOND_ID, 0, 5, 5, 0, 0, 0, 1L),
                new InventoryShard(FIRST_ID, 0, 6, 3, 3, 0, 0, 2L),
                new InventoryShard(FIRST_ID, 1, 4, 4, 0, 0, 0, 1L)));
        BatchAvailabilityQuery query = new BatchAvailabilityQuery(List.of(FIRST_ID, FIRST_ID, MISSING_ID, SECOND_ID));

        // When / Then
        StepVerifier.create(useCase.get(query))
                .expectNext(new EventInventory(FIRST_ID, 10, 7, 3, 0, 0))
                .expectNext(new EventInventory(SECOND_ID, 5, 5, 0, 0, 0))
                .verifyComplete();
        assertThat(eventIdsCaptor.getValue()).containsExactly(FIRST_ID, MISSING_ID, SECOND_ID);
        assertThat(eventsCaptor.getValue()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void shouldReturnNothingWithoutReadingInventoryWhenNoEventExists() {
        // Given
        when(eventRepository.findAllById(anySet())).thenReturn(Flux.empty());

        // When / Then
        StepVerifier.create(useCase.get(new BatchAvailabilityQuery(List.of(MISSING_ID))))
                .verifyComplete();
        verifyNoInteractions(inventoryRepository);
    }

    private static Event event(EventId eventId) {
        return Event.create(eventId, "Rock al Parque", new Venue("Parque Simon Bolivar", "Bogota"),
                Instant.parse("2026-12-01T20:00:00Z"), new Capacity(10), 2);
    }
}
