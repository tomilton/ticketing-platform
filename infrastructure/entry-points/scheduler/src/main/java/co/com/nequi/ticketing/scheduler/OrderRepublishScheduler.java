package co.com.nequi.ticketing.scheduler;

import co.com.nequi.ticketing.usecase.republishpendingorders.RepublishPendingOrdersUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Publishes again, every interval, the accepted orders whose publication failed. Only in the
 * {@code worker} role. The framework subscribes to the returned {@link Mono} and waits for it before
 * the next cycle, so there is no manual subscription.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class OrderRepublishScheduler {

    private final RepublishPendingOrdersUseCase republishPendingOrdersUseCase;

    /**
     * Runs one cycle of the republication. A failure is logged and the next cycle tries again.
     *
     * @return completion of the cycle
     */
    @Scheduled(fixedDelayString = "${orders.republish-interval}")
    public Mono<Void> republishPendingOrders() {
        return republishPendingOrdersUseCase.republish()
                .doOnNext(published -> log.debug("Pending orders published again, count={}", published))
                .doOnError(error -> log.error("Republication of pending orders failed", error))
                .onErrorResume(error -> Mono.empty())
                .then();
    }
}
