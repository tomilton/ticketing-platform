package co.com.nequi.ticketing.usecase.watchavailability;

import co.com.nequi.ticketing.model.config.AvailabilityWatchSettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.inventory.gateways.InventoryRepository;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchAvailabilityUseCaseTest {

    private static final EventId EVENT_ID = EventId.newId();
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Integer CAPACITY = 10;

    @Mock
    private InventoryRepository inventoryRepository;

    private WatchAvailabilityUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new WatchAvailabilityUseCase(new GetAvailabilityUseCase(inventoryRepository),
                new AvailabilityWatchSettings(POLL_INTERVAL));
    }

    @Test
    void shouldEmitOnlyChangesWhenInventoryIsPolled() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID))
                .thenReturn(shardWithAvailable(10), shardWithAvailable(10), shardWithAvailable(10),
                        shardWithAvailable(9));

        // When / Then
        StepVerifier.withVirtualTime(() -> useCase.watch(EVENT_ID))
                .expectSubscription()
                .expectNext(inventoryWithAvailable(10))
                .expectNoEvent(POLL_INTERVAL.multipliedBy(2))
                .thenAwait(POLL_INTERVAL)
                .expectNext(inventoryWithAvailable(9))
                .thenCancel()
                .verify();
        verify(inventoryRepository, times(4)).findShards(EVENT_ID);
    }

    @Test
    void shouldFailWithEventNotFoundWhenEventHasNoShards() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID)).thenReturn(Flux.empty());

        // When / Then
        StepVerifier.withVirtualTime(() -> useCase.watch(EVENT_ID))
                .expectSubscription()
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                        .isEqualTo(BusinessErrorMessage.EVENT_NOT_FOUND))
                .verify();
    }

    private static Flux<InventoryShard> shardWithAvailable(Integer available) {
        return Flux.just(new InventoryShard(EVENT_ID, 0, CAPACITY, available, CAPACITY - available, 0, 0, 1L));
    }

    private static EventInventory inventoryWithAvailable(Integer available) {
        return new EventInventory(EVENT_ID, CAPACITY, available, CAPACITY - available, 0, 0);
    }
}
