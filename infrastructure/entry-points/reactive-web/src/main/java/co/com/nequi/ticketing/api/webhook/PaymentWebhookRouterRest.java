package co.com.nequi.ticketing.api.webhook;

import co.com.nequi.ticketing.api.config.RouteProperties;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Route of the notices of the payment gateway, active only in the {@code api} role. It is public for
 * the security chain: the signature of each notice authenticates it.
 */
@Configuration
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class PaymentWebhookRouterRest {

    @Bean
    public RouterFunction<ServerResponse> paymentWebhookRoutes(PaymentNotificationHandler handler,
                                                               RouteProperties routes) {
        return SpringdocRouteBuilder.route()
                .POST(routes.paymentWebhookPath(), handler::receive, PaymentWebhookOpenApi::receive)
                .build();
    }
}
