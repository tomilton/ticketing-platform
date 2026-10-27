package co.com.nequi.ticketing.model.inventory.gateways;

import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.EventId;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Reads of the inventory shards. Every read is strongly consistent, so the availability is never
 * overestimated.
 */
public interface InventoryRepository {

    /**
     * Finds all the shards of an event.
     *
     * @param eventId the event
     * @return the shards, or empty if the event does not exist
     */
    Flux<InventoryShard> findShards(EventId eventId);

    /**
     * Finds all the shards of several events, using the shard count of each event.
     *
     * @param events the events
     * @return the shards of every event, in no particular order
     */
    Flux<InventoryShard> findShardsOf(List<Event> events);
}
