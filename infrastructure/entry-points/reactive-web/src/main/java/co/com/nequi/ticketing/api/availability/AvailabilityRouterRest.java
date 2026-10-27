package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.api.config.RouteProperties;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Routes of the availability resource, active only in the {@code api} role. They take precedence
 * over the event routes, and the batch route is declared first, so {@code /events/availability} is
 * never read as an event id.
 */
@Configuration
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class AvailabilityRouterRest {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public RouterFunction<ServerResponse> availabilityRoutes(AvailabilityHandler handler, RouteProperties routes) {
        return SpringdocRouteBuilder.route()
                .GET(routes.eventAvailabilityPath(), handler::getAvailability, AvailabilityOpenApi::getAvailability)
                .build();
    }
}
