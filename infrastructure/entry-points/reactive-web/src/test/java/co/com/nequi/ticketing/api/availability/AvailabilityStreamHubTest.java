package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.model.config.AvailabilityWatchSettings;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.inventory.gateways.InventoryRepository;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.watchavailability.WatchAvailabilityUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.test.StepVerifier;

import java.time.Duration;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityStreamHubTest {

    private static final EventId EVENT_ID = EventId.newId();
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Integer CAPACITY = 10;
    private static final Integer SUBSCRIBERS = 1_000;

    @Mock
    private InventoryRepository inventoryRepository;

    private WatchAvailabilityUseCase watchAvailabilityUseCase;
    private SimpleMeterRegistry meterRegistry;
    private AvailabilityStreamHub hub;

    @BeforeEach
    void setUp() {
        watchAvailabilityUseCase = spy(new WatchAvailabilityUseCase(new GetAvailabilityUseCase(inventoryRepository),
                new AvailabilityWatchSettings(POLL_INTERVAL)));
        meterRegistry = new SimpleMeterRegistry();
        hub = new AvailabilityStreamHub(watchAvailabilityUseCase, meterRegistry);
    }

    @Test
    void shouldPollOncePerIntervalWhenOneThousandClientsWatchTheSameEvent() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID)).thenAnswer(invocation -> shardWithAvailable(10));

        // When / Then
        StepVerifier.withVirtualTime(() -> Flux.range(0, SUBSCRIBERS)
                        .flatMap(index -> hub.watch(EVENT_ID), SUBSCRIBERS))
                .expectNextCount(SUBSCRIBERS)
                .thenAwait(Duration.ofSeconds(10))
                .then(() -> verify(inventoryRepository, times(11)).findShards(EVENT_ID))
                .then(() -> assertThat(subscribersGauge()).isEqualTo(SUBSCRIBERS.doubleValue()))
                .thenCancel()
                .verify();
        verify(watchAvailabilityUseCase, times(1)).watch(EVENT_ID);
        assertThat(subscribersGauge()).isZero();
    }

    @Test
    void shouldGiveLastValueToLateSubscriberWhenStreamIsAlreadyRunning() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID)).thenAnswer(invocation -> shardWithAvailable(10));

        // When / Then
        StepVerifier.withVirtualTime(() -> Flux.merge(hub.watch(EVENT_ID),
                        Mono.delay(Duration.ofMillis(5_500)).thenMany(hub.watch(EVENT_ID))))
                .expectNext(inventoryWithAvailable(10))
                .thenAwait(Duration.ofMillis(5_500))
                .expectNext(inventoryWithAvailable(10))
                .thenCancel()
                .verify();
    }

    @Test
    void shouldDeliverChangeWithinOneIntervalWhenInventoryChanges() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID)).thenReturn(shardWithAvailable(10), shardWithAvailable(9));

        // When / Then
        StepVerifier.withVirtualTime(() -> hub.watch(EVENT_ID))
                .expectNext(inventoryWithAvailable(10))
                .thenAwait(POLL_INTERVAL)
                .expectNext(inventoryWithAvailable(9))
                .thenCancel()
                .verify();
    }

    @Test
    void shouldStopPollingAndCreateNewStreamWhenLastSubscriberLeaves() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID)).thenAnswer(invocation -> shardWithAvailable(10));

        // When / Then
        StepVerifier.withVirtualTime(() -> hub.watch(EVENT_ID))
                .expectNext(inventoryWithAvailable(10))
                .thenCancel()
                .verify();
        StepVerifier.withVirtualTime(() -> Mono.delay(Duration.ofSeconds(5)).thenMany(hub.watch(EVENT_ID)))
                .thenAwait(Duration.ofSeconds(5))
                .expectNext(inventoryWithAvailable(10))
                .thenCancel()
                .verify();
        verify(inventoryRepository, times(2)).findShards(EVENT_ID);
        verify(watchAvailabilityUseCase, times(2)).watch(EVENT_ID);
    }

    @Test
    void shouldPropagateErrorToEverySubscriberAndReleaseStreamWhenReadFails() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID))
                .thenReturn(shardWithAvailable(10), Flux.error(DYNAMODB_ERROR.build()));

        // When / Then
        StepVerifier.withVirtualTime(() -> Flux.merge(hub.watch(EVENT_ID).materialize(),
                        hub.watch(EVENT_ID).materialize()))
                .expectNextMatches(Signal::isOnNext)
                .expectNextMatches(Signal::isOnNext)
                .thenAwait(POLL_INTERVAL)
                .expectNextMatches(Signal::isOnError)
                .expectNextMatches(Signal::isOnError)
                .verifyComplete();
        StepVerifier.withVirtualTime(() -> hub.watch(EVENT_ID))
                .expectError()
                .verify();
        verify(watchAvailabilityUseCase, times(2)).watch(EVENT_ID);
    }

    private double subscribersGauge() {
        return meterRegistry.get(AvailabilityStreamHub.SUBSCRIBERS_METRIC).gauge().value();
    }

    private static Flux<InventoryShard> shardWithAvailable(Integer available) {
        return Flux.just(new InventoryShard(EVENT_ID, 0, CAPACITY, available, CAPACITY - available, 0, 0, 1L));
    }

    private static EventInventory inventoryWithAvailable(Integer available) {
        return new EventInventory(EVENT_ID, CAPACITY, available, CAPACITY - available, 0, 0);
    }
}
