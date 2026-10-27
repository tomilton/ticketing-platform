package co.com.nequi.ticketing.api.support;

import co.com.nequi.ticketing.api.config.CorsConfig;
import co.com.nequi.ticketing.api.security.JwtDecoderConfig;
import co.com.nequi.ticketing.api.security.SecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Security of the route tests: the same filter chain as the application, with the tokens of
 * {@link TestTokens} verified in the local mode of the test configuration.
 */
@Configuration
@Import({SecurityConfig.class, JwtDecoderConfig.class, CorsConfig.class})
public class SecurityTestConfig {
}
