package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.config.RouteProperties;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Route of the complimentary tickets, active only in the {@code api} role.
 */
@Configuration
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class ComplimentaryRouterRest {

    @Bean
    public RouterFunction<ServerResponse> complimentaryRoutes(ComplimentaryHandler handler, RouteProperties routes) {
        return SpringdocRouteBuilder.route()
                .POST(routes.complimentaryPath(), handler::grantComplimentary, ComplimentaryOpenApi::grantComplimentary)
                .build();
    }
}
