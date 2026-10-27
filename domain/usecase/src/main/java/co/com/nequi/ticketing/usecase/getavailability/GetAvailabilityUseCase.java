package co.com.nequi.ticketing.usecase.getavailability;

import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.gateways.InventoryRepository;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;

/**
 * Gets the availability of an event as the sum of its shards; an event without shards does not exist.
 */
@RequiredArgsConstructor
public class GetAvailabilityUseCase {

    private final InventoryRepository inventoryRepository;

    public Mono<EventInventory> get(EventId eventId) {
        return inventoryRepository.findShards(eventId)
                .collectList()
                .filter(shards -> !shards.isEmpty())
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_FOUND.build())))
                .map(shards -> EventInventory.aggregate(eventId, shards));
    }
}
