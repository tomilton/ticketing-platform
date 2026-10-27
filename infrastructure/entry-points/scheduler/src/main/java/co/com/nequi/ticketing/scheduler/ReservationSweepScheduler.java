package co.com.nequi.ticketing.scheduler;

import co.com.nequi.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Releases, every interval, the reservations that expired without being processed. Only in the
 * {@code worker} role. The framework subscribes to the returned {@link Mono} and waits for it before
 * the next cycle, so there is no manual subscription.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class ReservationSweepScheduler {

    public static final String RELEASED_METRIC = "ticketing.orders.sweep.released";

    private final ExpireReservationsUseCase expireReservationsUseCase;
    private final MeterRegistry meterRegistry;

    /**
     * Runs one cycle of the sweep. A failure is logged and the next cycle tries again.
     *
     * @return completion of the cycle
     */
    @Scheduled(fixedDelayString = "${sweep.interval}")
    public Mono<Void> releaseExpiredReservations() {
        return expireReservationsUseCase.expire()
                .doOnNext(released -> {
                    log.info("Reservation sweep finished, released={}", released);
                    meterRegistry.counter(RELEASED_METRIC).increment(released);
                })
                .doOnError(error -> log.error("Reservation sweep failed", error))
                .onErrorResume(error -> Mono.empty())
                .then();
    }
}
