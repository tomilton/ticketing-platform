package co.com.nequi.ticketing.usecase.getevent;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetEventUseCaseTest {

    private static final EventId EVENT_ID = EventId.newId();

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private GetEventUseCase useCase;

    @Test
    void shouldReturnEventWhenEventExists() {
        // Given
        Event expected = Event.create(EVENT_ID, "Rock al Parque", new Venue("Parque Simon Bolivar", "Bogota"),
                Instant.parse("2026-12-01T20:00:00Z"), new Capacity(100), 1);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.just(expected));

        // When / Then
        StepVerifier.create(useCase.get(EVENT_ID))
                .assertNext(event -> assertThat(event).usingRecursiveComparison().isEqualTo(expected))
                .verifyComplete();
    }

    @Test
    void shouldFailWithEventNotFoundWhenEventDoesNotExist() {
        // Given
        when(eventRepository.findById(EVENT_ID)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.get(EVENT_ID))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                        .isEqualTo(BusinessErrorMessage.EVENT_NOT_FOUND))
                .verify();
    }
}
