package co.com.nequi.ticketing.scheduler;

import co.com.nequi.ticketing.scheduler.config.SchedulingConfig;
import co.com.nequi.ticketing.usecase.republishpendingorders.RepublishPendingOrdersUseCase;
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
class OrderRepublishSchedulerTest {

    private static final long WAIT_SECONDS = 5;

    @Mock
    private RepublishPendingOrdersUseCase useCase;

    @Test
    void shouldInvokeRepublicationWhenCycleRuns() {
        // Given
        when(useCase.republish()).thenReturn(Mono.just(3L));

        // When / Then
        StepVerifier.create(new OrderRepublishScheduler(useCase).republishPendingOrders())
                .verifyComplete();
    }

    @Test
    void shouldCompleteWithoutPropagatingWhenRepublicationFails() {
        // Given
        when(useCase.republish()).thenReturn(Mono.error(new IllegalStateException("database down")));

        // When / Then
        StepVerifier.create(new OrderRepublishScheduler(useCase).republishPendingOrders())
                .verifyComplete();
    }

    @Test
    void shouldSubscribeToReturnedMonoOnEveryCycleWhenRoleIsWorker() throws InterruptedException {
        // Given
        CountDownLatch cycles = new CountDownLatch(2);
        RepublishPendingOrdersUseCase scheduled = mock(RepublishPendingOrdersUseCase.class);
        when(scheduled.republish()).thenReturn(Mono.fromSupplier(() -> {
            cycles.countDown();
            return 0L;
        }));
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, OrderRepublishScheduler.class)
                .withBean(RepublishPendingOrdersUseCase.class, () -> scheduled)
                .withPropertyValues("app.role=worker", "orders.republish-interval=50ms");

        // When / Then
        runner.run(context -> assertThat(cycles.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue());
    }

    @Test
    void shouldNotRegisterSchedulerWhenRoleIsApi() {
        // When / Then
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, OrderRepublishScheduler.class)
                .withBean(RepublishPendingOrdersUseCase.class, () -> useCase)
                .withPropertyValues("app.role=api", "orders.republish-interval=50ms")
                .run(context -> assertThat(context).doesNotHaveBean(OrderRepublishScheduler.class));
    }
}
