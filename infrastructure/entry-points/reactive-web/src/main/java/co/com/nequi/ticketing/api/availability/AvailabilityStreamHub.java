package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.watchavailability.WatchAvailabilityUseCase;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shares one availability poll per event among all the subscribers of this instance.
 * <p>
 * The map of streams is shared mutable state on purpose (deviation 1 of the plan): it is the only
 * way to read the inventory once per interval no matter how many clients watch the event, and to
 * stop reading when the last one leaves. It stays inside this class, is thread safe and removes an
 * entry only if it still holds the same stream.
 */
@Component
public class AvailabilityStreamHub {

    static final String SUBSCRIBERS_METRIC = "ticketing.availability.stream.subscribers";
    private static final int REPLAYED_VALUES = 1;

    private final ConcurrentMap<EventId, Flux<EventInventory>> streams = new ConcurrentHashMap<>();
    private final AtomicInteger subscribers = new AtomicInteger();
    private final WatchAvailabilityUseCase watchAvailabilityUseCase;

    public AvailabilityStreamHub(WatchAvailabilityUseCase watchAvailabilityUseCase, MeterRegistry meterRegistry) {
        this.watchAvailabilityUseCase = watchAvailabilityUseCase;
        Gauge.builder(SUBSCRIBERS_METRIC, subscribers, AtomicInteger::get)
                .description("Active subscribers of the availability stream in this instance")
                .register(meterRegistry);
    }

    public Flux<EventInventory> watch(EventId eventId) {
        return Flux.defer(() -> streams.computeIfAbsent(eventId, this::share))
                .doOnSubscribe(subscription -> subscribers.incrementAndGet())
                .doFinally(signal -> subscribers.decrementAndGet());
    }

    private Flux<EventInventory> share(EventId eventId) {
        AtomicReference<Flux<EventInventory>> shared = new AtomicReference<>();
        shared.set(watchAvailabilityUseCase.watch(eventId)
                .doFinally(signal -> streams.remove(eventId, shared.get()))
                .replay(REPLAYED_VALUES)
                .refCount());
        return shared.get();
    }
}
