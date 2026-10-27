package co.com.nequi.ticketing.usecase.getbatchavailability;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.inventory.gateways.InventoryRepository;
import co.com.nequi.ticketing.model.inventory.request.BatchAvailabilityQuery;
import co.com.nequi.ticketing.model.shared.EventId;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gets the availability of several events. Repeated ids are counted once, missing events are left
 * out and the result keeps the order of the first appearance of each id in the request.
 */
@RequiredArgsConstructor
public class GetBatchAvailabilityUseCase {

    private final EventRepository eventRepository;
    private final InventoryRepository inventoryRepository;

    public Flux<EventInventory> get(BatchAvailabilityQuery query) {
        Set<EventId> eventIds = new LinkedHashSet<>(query.eventIds());
        return eventRepository.findAllById(eventIds)
                .collectList()
                .filter(events -> !events.isEmpty())
                .flatMap(this::findShardsByEvent)
                .flatMapMany(shardsByEvent -> Flux.fromIterable(eventIds)
                        .filter(shardsByEvent::containsKey)
                        .map(eventId -> EventInventory.aggregate(eventId, shardsByEvent.get(eventId))));
    }

    private Mono<Map<EventId, List<InventoryShard>>> findShardsByEvent(List<Event> events) {
        return inventoryRepository.findShardsOf(events)
                .collect(Collectors.groupingBy(InventoryShard::eventId));
    }
}
