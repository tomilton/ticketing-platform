package co.com.nequi.ticketing.usecase.createevent;

import co.com.nequi.ticketing.model.config.InventorySettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.command.CreateEventCommand;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateEventUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant FUTURE_DATE = Instant.parse("2026-12-01T20:00:00Z");
    private static final Integer SHARD_COUNT = 3;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ClockProvider clockProvider;

    @Captor
    private ArgumentCaptor<List<InventoryShard>> shardsCaptor;

    private CreateEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateEventUseCase(eventRepository, clockProvider, new InventorySettings(SHARD_COUNT));
    }

    @Test
    void shouldCreateActiveEventWithShardsWhenDateIsInFuture() {
        // Given
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.create(any(Event.class), shardsCaptor.capture()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // When / Then
        StepVerifier.create(useCase.create(command(FUTURE_DATE, 10)))
                .assertNext(event -> {
                    assertThat(event.getName()).isEqualTo("Rock al Parque");
                    assertThat(event.getVenue()).isEqualTo(new Venue("Parque Simon Bolivar", "Bogota"));
                    assertThat(event.getStatus()).isEqualTo(EventStatus.ACTIVE);
                    assertThat(event.getShardCount()).isEqualTo(SHARD_COUNT);
                    assertThat(shardsCaptor.getValue()).extracting(InventoryShard::capacity).containsExactly(4, 3, 3);
                    assertThat(shardsCaptor.getValue()).allMatch(shard -> shard.eventId().equals(event.getEventId()));
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldFailWithDateNotInFutureWhenDateIsNotAfterNow(Long secondsFromNow) {
        // Given
        when(clockProvider.now()).thenReturn(NOW);

        // When / Then
        StepVerifier.create(useCase.create(command(NOW.plusSeconds(secondsFromNow), 10)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                        .isEqualTo(BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE))
                .verify();
        verifyNoInteractions(eventRepository);
    }

    @Test
    void shouldCreateOneShardPerTicketWhenCapacityIsLowerThanShardCount() {
        // Given
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.create(any(Event.class), shardsCaptor.capture()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // When / Then
        StepVerifier.create(useCase.create(command(FUTURE_DATE, 2)))
                .assertNext(event -> {
                    assertThat(event.getShardCount()).isEqualTo(2);
                    assertThat(shardsCaptor.getValue()).extracting(InventoryShard::capacity).containsExactly(1, 1);
                })
                .verifyComplete();
    }

    @Test
    void shouldPropagateErrorWhenRepositoryFails() {
        // Given
        IllegalStateException failure = new IllegalStateException("database down");
        when(clockProvider.now()).thenReturn(NOW);
        when(eventRepository.create(any(Event.class), anyList())).thenReturn(Mono.error(failure));

        // When / Then
        StepVerifier.create(useCase.create(command(FUTURE_DATE, 10)))
                .expectErrorMatches(error -> error == failure)
                .verify();
    }

    private static CreateEventCommand command(Instant date, Integer capacity) {
        return new CreateEventCommand("Rock al Parque", "Parque Simon Bolivar", "Bogota", date, capacity);
    }
}
