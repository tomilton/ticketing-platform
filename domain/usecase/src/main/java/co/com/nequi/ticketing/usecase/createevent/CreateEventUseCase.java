package co.com.nequi.ticketing.usecase.createevent;

import co.com.nequi.ticketing.model.config.InventorySettings;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.command.CreateEventCommand;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryShard;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.List;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE;

/**
 * Creates an active event with a future date and splits its capacity into inventory shards.
 */
@RequiredArgsConstructor
public class CreateEventUseCase {

    private final EventRepository eventRepository;
    private final ClockProvider clockProvider;
    private final InventorySettings inventorySettings;

    public Mono<Event> create(CreateEventCommand command) {
        return validateDateIsInFuture(command)
                .map(this::buildEvent)
                .flatMap(event -> eventRepository.create(event, buildShards(event)));
    }

    private Mono<CreateEventCommand> validateDateIsInFuture(CreateEventCommand command) {
        return Mono.fromSupplier(clockProvider::now)
                .filter(now -> command.date().isAfter(now))
                .map(now -> command)
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_DATE_NOT_IN_FUTURE.build())));
    }

    private Event buildEvent(CreateEventCommand command) {
        return Event.create(EventId.newId(), command.name(), new Venue(command.venueName(), command.city()),
                command.date(), new Capacity(command.capacity()), inventorySettings.shardCount());
    }

    private List<InventoryShard> buildShards(Event event) {
        return EventInventory.initialShards(event.getEventId(), event.getCapacity(), event.getShardCount());
    }
}
