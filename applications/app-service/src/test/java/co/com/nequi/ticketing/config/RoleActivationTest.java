package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.api.availability.AvailabilityHandler;
import co.com.nequi.ticketing.api.availability.AvailabilityRouterRest;
import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.event.EventHandler;
import co.com.nequi.ticketing.api.event.EventRouterRest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoleActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EventRouterRest.class, AvailabilityRouterRest.class)
            .withBean(EventHandler.class, () -> mock(EventHandler.class))
            .withBean(AvailabilityHandler.class, () -> mock(AvailabilityHandler.class))
            .withBean(RouteProperties.class, () -> new RouteProperties("/api/v1", "/events", "/events/{eventId}",
                    "/events/{eventId}/availability", "/events/availability", "/events/{eventId}/availability/stream",
                    "/orders", "/orders/{orderId}"));

    @Test
    void shouldNotRegisterApiRoutesWhenRoleIsWorker() {
        // When / Then
        contextRunner.withPropertyValues("app.role=worker")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(EventRouterRest.class)
                        .doesNotHaveBean(AvailabilityRouterRest.class));
    }

    @Test
    void shouldRegisterApiRoutesWhenRoleIsApi() {
        // When / Then
        contextRunner.withPropertyValues("app.role=api")
                .run(context -> assertThat(context)
                        .hasSingleBean(EventRouterRest.class)
                        .hasSingleBean(AvailabilityRouterRest.class));
    }
}
