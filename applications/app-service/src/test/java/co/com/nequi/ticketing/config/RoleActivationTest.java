package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.api.availability.AvailabilityHandler;
import co.com.nequi.ticketing.api.availability.AvailabilityRouterRest;
import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.event.EventHandler;
import co.com.nequi.ticketing.api.event.EventRouterRest;
import co.com.nequi.ticketing.api.order.ComplimentaryHandler;
import co.com.nequi.ticketing.api.order.ComplimentaryRouterRest;
import co.com.nequi.ticketing.scheduler.ReservationSweepScheduler;
import co.com.nequi.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoleActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EventRouterRest.class, AvailabilityRouterRest.class, ComplimentaryRouterRest.class,
                    ReservationSweepScheduler.class)
            .withBean(EventHandler.class, () -> mock(EventHandler.class))
            .withBean(AvailabilityHandler.class, () -> mock(AvailabilityHandler.class))
            .withBean(ComplimentaryHandler.class, () -> mock(ComplimentaryHandler.class))
            .withBean(ExpireReservationsUseCase.class, () -> mock(ExpireReservationsUseCase.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RouteProperties.class, () -> new RouteProperties("/api/v1", "/events", "/events/{eventId}",
                    "/events/{eventId}/availability", "/events/availability", "/events/{eventId}/availability/stream",
                    "/orders", "/orders/{orderId}", "/events/{eventId}/complimentary",
                    "/webhooks/payments"));

    @Test
    void shouldRegisterOnlySweepWhenRoleIsWorker() {
        // When / Then
        contextRunner.withPropertyValues("app.role=worker", "sweep.interval=1h")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(EventRouterRest.class)
                        .doesNotHaveBean(AvailabilityRouterRest.class)
                        .doesNotHaveBean(ComplimentaryRouterRest.class)
                        .hasSingleBean(ReservationSweepScheduler.class));
    }

    @Test
    void shouldRegisterApiRoutesWithoutSweepWhenRoleIsApi() {
        // When / Then
        contextRunner.withPropertyValues("app.role=api")
                .run(context -> assertThat(context)
                        .hasSingleBean(EventRouterRest.class)
                        .hasSingleBean(AvailabilityRouterRest.class)
                        .hasSingleBean(ComplimentaryRouterRest.class)
                        .doesNotHaveBean(ReservationSweepScheduler.class));
    }
}
