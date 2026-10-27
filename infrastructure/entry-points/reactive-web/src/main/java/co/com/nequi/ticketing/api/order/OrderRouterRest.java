package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.config.RouteProperties;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Routes of the order resource, active only in the {@code api} role.
 */
@Configuration
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class OrderRouterRest {

    @Bean
    public RouterFunction<ServerResponse> orderRoutes(OrderHandler handler, RouteProperties routes) {
        return SpringdocRouteBuilder.route()
                .POST(routes.ordersPath(), handler::placeOrder, OrderOpenApi::placeOrder)
                .GET(routes.orderByIdPath(), handler::getOrder, OrderOpenApi::getOrder)
                .build();
    }
}
