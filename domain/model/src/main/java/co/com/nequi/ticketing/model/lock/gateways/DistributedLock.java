package co.com.nequi.ticketing.model.lock.gateways;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Lock shared by the replicas, held for a lease, so a periodic task runs in one replica at a time.
 * If the holder dies, another replica takes the lock once the lease ends.
 */
public interface DistributedLock {

    /**
     * Takes the lock if it is free, its lease ended or this replica already holds it.
     *
     * @param lockName the name of the lock
     * @param lease    how long the lock is held if nobody releases it
     * @return {@code true} if this replica holds the lock; {@code false} if another one holds it
     */
    Mono<Boolean> tryAcquire(String lockName, Duration lease);

    /**
     * Frees the lock if this replica still holds it; otherwise it does nothing.
     *
     * @param lockName the name of the lock
     * @return completion when the lock is free
     */
    Mono<Void> release(String lockName);
}
