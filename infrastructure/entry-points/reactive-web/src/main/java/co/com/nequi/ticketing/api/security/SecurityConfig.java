package co.com.nequi.ticketing.api.security;

import co.com.nequi.ticketing.api.config.RouteProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.FORBIDDEN;
import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.UNAUTHORIZED;

/**
 * Security of the API: stateless, without sessions, CSRF, basic or form login. The reads of the
 * catalog and the availability and the health of the service are public; creating events and granting
 * complimentary tickets need the administrator role; every other route needs a valid bearer token.
 * A rejection becomes an error of the catalog, rendered by the global handler.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({JwtProperties.class, RouteProperties.class})
public class SecurityConfig {

    static final String ROLE_PREFIX = "ROLE_";
    private static final String HEALTH = "/actuator/health/**";
    private static final String BEARER = "Bearer";
    private static final ServerAuthenticationEntryPoint ENTRY_POINT = (exchange, error) -> Mono.fromRunnable(
                    () -> exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, BEARER))
            .then(Mono.defer(() -> Mono.error(UNAUTHORIZED.build(error))));
    private static final ServerAccessDeniedHandler ACCESS_DENIED_HANDLER =
            (exchange, error) -> Mono.error(FORBIDDEN.build(error));

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, RouteProperties routes,
                                                  JwtProperties jwt, CorsConfigurationSource corsSource) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .cors(cors -> cors.configurationSource(corsSource))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, routes.eventsPath(), routes.eventByIdPath(),
                                routes.eventAvailabilityPath(), routes.batchAvailabilityPath(),
                                routes.availabilityStreamPath(), HEALTH).permitAll()
                        .pathMatchers(HttpMethod.POST, routes.eventsPath(), routes.complimentaryPath())
                        .hasRole(jwt.adminRole())
                        .anyExchange().authenticated())
                .oauth2ResourceServer(server -> server
                        .authenticationEntryPoint(ENTRY_POINT)
                        .accessDeniedHandler(ACCESS_DENIED_HANDLER)
                        .jwt(token -> token.jwtAuthenticationConverter(authenticationConverter(jwt))))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(ENTRY_POINT)
                        .accessDeniedHandler(ACCESS_DENIED_HANDLER))
                .build();
    }

    /**
     * Turns every value of the roles claim into a role, so the administrator routes can require it.
     */
    private static ReactiveJwtAuthenticationConverterAdapter authenticationConverter(JwtProperties jwt) {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(jwt.rolesClaim());
        authorities.setAuthorityPrefix(ROLE_PREFIX);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
