package co.com.nequi.ticketing.usecase.listactiveevents;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.EventPage;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.event.request.EventPageQuery;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListActiveEventsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final EventPageQuery QUERY = new EventPageQuery(20, "cursor");

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ClockProvider clockProvider;

    @InjectMocks
    private ListActiveEventsUseCase useCase;

    @Test
    void shouldListEventsAfterNowWhenQueryIsReceived() {
        // Given
        Event event = Event.create(EventId.newId(), "Rock al Parque", new Venue("Parque Simon Bolivar", "Bogota"),
                Instant.parse("2026-12-01T20:00:00Z"), new Capacity(100), 1);
        EventPage expected = new EventPage(List.of(event), "next");
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.findListable(NOW, QUERY)).thenReturn(Mono.just(expected));

        // When / Then
        StepVerifier.create(useCase.list(QUERY))
                .assertNext(page -> assertThat(page).usingRecursiveComparison().isEqualTo(expected))
                .verifyComplete();
    }

    @Test
    void shouldEmitEmptyPageWhenThereAreNoEventsOnSale() {
        // Given
        EventPage empty = new EventPage(List.of(), null);
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.findListable(NOW, QUERY)).thenReturn(Mono.just(empty));

        // When / Then
        StepVerifier.create(useCase.list(QUERY))
                .assertNext(page -> assertThat(page).isEqualTo(empty))
                .verifyComplete();
    }
}
