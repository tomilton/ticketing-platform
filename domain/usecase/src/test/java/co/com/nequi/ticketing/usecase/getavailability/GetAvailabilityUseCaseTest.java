package co.com.nequi.ticketing.usecase.getavailability;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.inventory.gateways.InventoryRepository;
import co.com.nequi.ticketing.model.shared.EventId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAvailabilityUseCaseTest {

    private static final EventId EVENT_ID = EventId.newId();

    @Mock
    private InventoryRepository inventoryRepository;

    @InjectMocks
    private GetAvailabilityUseCase useCase;

    @Test
    void shouldAddShardsWhenEventHasShards() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID)).thenReturn(Flux.just(
                new InventoryShard(EVENT_ID, 0, 10, 4, 3, 2, 1, 2L),
                new InventoryShard(EVENT_ID, 1, 5, 5, 0, 0, 0, 1L)));

        // When / Then
        StepVerifier.create(useCase.get(EVENT_ID))
                .expectNext(new EventInventory(EVENT_ID, 15, 9, 3, 2, 1))
                .verifyComplete();
    }

    @Test
    void shouldFailWithEventNotFoundWhenEventHasNoShards() {
        // Given
        when(inventoryRepository.findShards(EVENT_ID)).thenReturn(Flux.empty());

        // When / Then
        StepVerifier.create(useCase.get(EVENT_ID))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                        .isEqualTo(BusinessErrorMessage.EVENT_NOT_FOUND))
                .verify();
    }
}
