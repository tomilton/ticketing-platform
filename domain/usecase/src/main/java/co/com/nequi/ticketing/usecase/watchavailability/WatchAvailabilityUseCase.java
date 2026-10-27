package co.com.nequi.ticketing.usecase.watchavailability;

import co.com.nequi.ticketing.model.config.AvailabilityWatchSettings;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * Watches the availability of an event: reads it every poll interval and emits only the changes.
 * The flux is cold; sharing it among subscribers is a concern of the entry point.
 */
@RequiredArgsConstructor
public class WatchAvailabilityUseCase {

    private final GetAvailabilityUseCase getAvailabilityUseCase;
    private final AvailabilityWatchSettings availabilityWatchSettings;

    public Flux<EventInventory> watch(EventId eventId) {
        return Flux.interval(Duration.ZERO, availabilityWatchSettings.pollInterval())
                .onBackpressureDrop()
                .concatMap(tick -> getAvailabilityUseCase.get(eventId))
                .distinctUntilChanged();
    }
}
