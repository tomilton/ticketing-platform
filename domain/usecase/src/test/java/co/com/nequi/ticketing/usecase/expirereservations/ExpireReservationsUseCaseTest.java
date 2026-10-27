package co.com.nequi.ticketing.usecase.expirereservations;

import co.com.nequi.ticketing.model.config.SweepSettings;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.lock.gateways.DistributedLock;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpireReservationsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Integer BATCH_SIZE = 500;
    private static final String LOCK_NAME = "reservation-sweep";

    @Mock
    private DistributedLock distributedLock;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProcessOrderUseCase processOrderUseCase;

    private ExpireReservationsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ExpireReservationsUseCase(distributedLock, orderRepository, processOrderUseCase, () -> NOW,
                new SweepSettings(LEASE, BATCH_SIZE, 2));
    }

    @Test
    void shouldReturnZeroWithoutQueryingWhenAnotherReplicaHoldsTheLock() {
        // Given
        when(distributedLock.tryAcquire(LOCK_NAME, LEASE)).thenReturn(Mono.just(Boolean.FALSE));

        // When / Then
        StepVerifier.create(useCase.expire())
                .expectNext(0L)
                .verifyComplete();
        verify(orderRepository, never()).findExpired(any(), any());
        verify(distributedLock, never()).release(any());
    }

    @Test
    void shouldCountExpiredOrdersAndReleaseLockWhenLockIsTaken() {
        // Given
        OrderId first = OrderId.newId();
        OrderId second = OrderId.newId();
        OrderId third = OrderId.newId();
        when(distributedLock.tryAcquire(LOCK_NAME, LEASE)).thenReturn(Mono.just(Boolean.TRUE));
        when(orderRepository.findExpired(NOW, BATCH_SIZE)).thenReturn(Flux.just(first, second, third));
        when(processOrderUseCase.process(first)).thenReturn(Mono.just(ProcessingOutcome.EXPIRED));
        when(processOrderUseCase.process(second)).thenReturn(Mono.just(ProcessingOutcome.IGNORED));
        when(processOrderUseCase.process(third)).thenReturn(Mono.just(ProcessingOutcome.EXPIRED));
        when(distributedLock.release(LOCK_NAME)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.expire())
                .expectNext(2L)
                .verifyComplete();
        verify(distributedLock).release(LOCK_NAME);
    }

    @Test
    void shouldKeepExpiringOtherOrdersWhenOneOrderFails() {
        // Given
        OrderId failing = OrderId.newId();
        OrderId expiring = OrderId.newId();
        when(distributedLock.tryAcquire(LOCK_NAME, LEASE)).thenReturn(Mono.just(Boolean.TRUE));
        when(orderRepository.findExpired(NOW, BATCH_SIZE)).thenReturn(Flux.just(failing, expiring));
        when(processOrderUseCase.process(failing)).thenReturn(Mono.error(new BusinessException(ORDER_NOT_FOUND)));
        when(processOrderUseCase.process(expiring)).thenReturn(Mono.just(ProcessingOutcome.EXPIRED));
        when(distributedLock.release(LOCK_NAME)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.expire())
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void shouldReleaseLockAndFailWhenQueryFails() {
        // Given
        IllegalStateException failure = new IllegalStateException("query failed");
        when(distributedLock.tryAcquire(LOCK_NAME, LEASE)).thenReturn(Mono.just(Boolean.TRUE));
        when(orderRepository.findExpired(NOW, BATCH_SIZE)).thenReturn(Flux.error(failure));
        when(distributedLock.release(LOCK_NAME)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.expire())
                .expectErrorMatches(error -> error == failure)
                .verify();
        verify(distributedLock).release(LOCK_NAME);
    }

    @Test
    void shouldCompleteCycleWhenLockReleaseFails() {
        // Given
        when(distributedLock.tryAcquire(LOCK_NAME, LEASE)).thenReturn(Mono.just(Boolean.TRUE));
        when(orderRepository.findExpired(NOW, BATCH_SIZE)).thenReturn(Flux.empty());
        when(distributedLock.release(LOCK_NAME)).thenReturn(Mono.error(new IllegalStateException("release failed")));

        // When / Then
        StepVerifier.create(useCase.expire())
                .expectNext(0L)
                .verifyComplete();
    }

    @Test
    void shouldReleaseLockWhenCycleIsCancelled() {
        // Given
        when(distributedLock.tryAcquire(LOCK_NAME, LEASE)).thenReturn(Mono.just(Boolean.TRUE));
        when(orderRepository.findExpired(NOW, BATCH_SIZE)).thenReturn(Flux.never());
        when(distributedLock.release(LOCK_NAME)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.expire())
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify();
        verify(distributedLock).release(LOCK_NAME);
    }
}
