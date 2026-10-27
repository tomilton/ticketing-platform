package co.com.nequi.ticketing.usecase.expirereservations;

import co.com.nequi.ticketing.model.config.SweepSettings;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.lock.gateways.DistributedLock;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * Releases the reservations that expired without being processed. Only the replica that holds the
 * sweep lock works in a cycle, and at most a batch of orders. Each order is released by the order
 * processing itself, whose conditional transition moves the inventory only once even if a message
 * processes the same order at the same time.
 */
@RequiredArgsConstructor
public class ExpireReservationsUseCase {

    static final String LOCK_NAME = "reservation-sweep";

    private final DistributedLock distributedLock;
    private final OrderRepository orderRepository;
    private final ProcessOrderUseCase processOrderUseCase;
    private final ClockProvider clockProvider;
    private final SweepSettings sweepSettings;

    /**
     * Runs one cycle of the sweep. A failed order does not stop the others; the next cycle takes it
     * again. The lock is released when the cycle ends, also after an error; a failed release only
     * leaves the lock until its lease ends, so it does not fail the cycle.
     *
     * @return how many orders this cycle expired; {@code 0} if another replica holds the lock
     */
    public Mono<Long> expire() {
        return distributedLock.tryAcquire(LOCK_NAME, sweepSettings.lockLease())
                .filter(Boolean::booleanValue)
                .flatMap(acquired -> Mono.usingWhen(Mono.just(LOCK_NAME), lockName -> expireBatch(),
                        this::release, (lockName, error) -> release(lockName), this::release))
                .defaultIfEmpty(0L);
    }

    private Mono<Long> expireBatch() {
        return Mono.fromSupplier(clockProvider::now)
                .flatMapMany(now -> orderRepository.findExpired(now, sweepSettings.batchSize()))
                .flatMap(orderId -> processOrderUseCase.process(orderId)
                                .onErrorResume(error -> Mono.just(ProcessingOutcome.IGNORED)),
                        sweepSettings.concurrency())
                .filter(outcome -> outcome == ProcessingOutcome.EXPIRED)
                .count();
    }

    private Mono<Void> release(String lockName) {
        return distributedLock.release(lockName).onErrorResume(error -> Mono.empty());
    }
}
