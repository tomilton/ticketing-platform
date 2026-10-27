package co.com.nequi.ticketing.api.security;

import co.com.nequi.ticketing.model.shared.AuthenticatedUser;
import co.com.nequi.ticketing.model.shared.CustomerId;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.UNAUTHORIZED;

/**
 * Turns the verified token of the request into the caller the use cases know: the subject is the
 * customer and the roles claim tells whether the caller is an administrator.
 */
@Component
public class AuthenticatedUserResolver {

    private final String adminAuthority;

    public AuthenticatedUserResolver(JwtProperties properties) {
        this.adminAuthority = SecurityConfig.ROLE_PREFIX + properties.adminRole();
    }

    public Mono<AuthenticatedUser> resolve() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .ofType(JwtAuthenticationToken.class)
                .flatMap(token -> Mono.fromCallable(() -> UUID.fromString(token.getName()))
                        .onErrorMap(IllegalArgumentException.class, UNAUTHORIZED::build)
                        .map(subject -> new AuthenticatedUser(new CustomerId(subject), isAdmin(token))))
                .switchIfEmpty(Mono.defer(() -> Mono.error(UNAUTHORIZED.build())));
    }

    private Boolean isAdmin(JwtAuthenticationToken token) {
        return token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(adminAuthority::equals);
    }
}
