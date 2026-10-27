package co.com.nequi.ticketing.scheduler;

import co.com.nequi.ticketing.scheduler.config.SchedulingConfig;
import co.com.nequi.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSweepSchedulerTest {

    private static final long WAIT_SECONDS = 5;

    @Mock
    private ExpireReservationsUseCase useCase;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void shouldCountReleasedOrdersWhenCycleRuns() {
        // Given
        when(useCase.expire()).thenReturn(Mono.just(3L));

        // When / Then
        StepVerifier.create(new ReservationSweepScheduler(useCase, meterRegistry).releaseExpiredReservations())
                .verifyComplete();
        assertThat(meterRegistry.counter(ReservationSweepScheduler.RELEASED_METRIC).count()).isEqualTo(3.0);
    }

    @Test
    void shouldCompleteWithoutPropagatingWhenSweepFails() {
        // Given
        when(useCase.expire()).thenReturn(Mono.error(new IllegalStateException("database down")));

        // When / Then
        StepVerifier.create(new ReservationSweepScheduler(useCase, meterRegistry).releaseExpiredReservations())
                .verifyComplete();
        assertThat(meterRegistry.counter(ReservationSweepScheduler.RELEASED_METRIC).count()).isZero();
    }

    @Test
    void shouldSubscribeToReturnedMonoOnEveryCycleWhenRoleIsWorker() {
        // Given
        CountDownLatch cycles = new CountDownLatch(2);
        ExpireReservationsUseCase scheduled = mock(ExpireReservationsUseCase.class);
        when(scheduled.expire()).thenReturn(Mono.fromSupplier(() -> {
            cycles.countDown();
            return 0L;
        }));
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, ReservationSweepScheduler.class)
                .withBean(ExpireReservationsUseCase.class, () -> scheduled)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("app.role=worker", "sweep.interval=50ms");

        // When / Then
        runner.run(context -> assertThat(cycles.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue());
    }

    @Test
    void shouldNotRegisterSchedulerWhenRoleIsApi() {
        // When / Then
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, ReservationSweepScheduler.class)
                .withBean(ExpireReservationsUseCase.class, () -> useCase)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("app.role=api", "sweep.interval=50ms")
                .run(context -> assertThat(context).doesNotHaveBean(ReservationSweepScheduler.class));
    }
}
