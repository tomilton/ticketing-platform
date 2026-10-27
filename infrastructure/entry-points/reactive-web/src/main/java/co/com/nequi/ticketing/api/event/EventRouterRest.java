package co.com.nequi.ticketing.api.event;

import co.com.nequi.ticketing.api.config.RouteProperties;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Routes of the event resource, active only in the {@code api} role.
 */
@Configuration
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class EventRouterRest {

    @Bean
    public RouterFunction<ServerResponse> eventRoutes(EventHandler handler, RouteProperties routes) {
        return SpringdocRouteBuilder.route()
                .POST(routes.eventsPath(), handler::createEvent, EventOpenApi::createEvent)
                .GET(routes.eventsPath(), handler::listEvents, EventOpenApi::listEvents)
                .GET(routes.eventByIdPath(), handler::getEvent, EventOpenApi::getEvent)
                .build();
    }
}
