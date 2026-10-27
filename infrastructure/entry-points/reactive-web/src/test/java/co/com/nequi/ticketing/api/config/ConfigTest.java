package co.com.nequi.ticketing.api.config;

import co.com.nequi.ticketing.api.support.AuthenticatedClientConfig;
import co.com.nequi.ticketing.api.support.SecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@ContextConfiguration(classes = ConfigTest.TestRouter.class)
@WebFluxTest
@Import({SecurityTestConfig.class, AuthenticatedClientConfig.class, SecurityHeadersConfig.class})
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:4200")
class ConfigTest {

    private static final String TEST_PATH = "/test";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldAddSecurityHeadersWhenAnyRouteResponds() {
        webTestClient.get()
                .uri(TEST_PATH)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Security-Policy",
                        "default-src 'self'; frame-ancestors 'self'; form-action 'self'")
                .expectHeader().valueEquals("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().doesNotExist("Server")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("Pragma", "no-cache")
                .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin");
    }

    @Configuration
    static class TestRouter {

        @Bean
        RouterFunction<ServerResponse> testRoute() {
            return route(GET(TEST_PATH), request -> ServerResponse.ok().build());
        }
    }
}
