package co.com.nequi.ticketing.api.support;

import org.springframework.boot.webtestclient.autoconfigure.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * Sends the token of {@link TestTokens#customerWithAdminRole()} in every request of the route tests
 * that verify the behavior of a route and not its security.
 */
@Configuration
public class AuthenticatedClientConfig {

    @Bean
    WebTestClientBuilderCustomizer bearerTokenCustomizer() {
        return builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                TestTokens.bearer(TestTokens.customerWithAdminRole()));
    }
}
