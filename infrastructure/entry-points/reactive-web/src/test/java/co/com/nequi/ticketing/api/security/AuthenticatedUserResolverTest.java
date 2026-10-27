package co.com.nequi.ticketing.api.security;

import co.com.nequi.ticketing.api.support.TestTokens;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.shared.AuthenticatedUser;
import co.com.nequi.ticketing.model.shared.CustomerId;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedUserResolverTest {

    private static final String ADMIN_AUTHORITY = "ROLE_" + TestTokens.ADMIN_ROLE;
    private static final JwtProperties PROPERTIES = new JwtProperties(JwtMode.LOCAL_HS256, "http://localhost/jwks",
            TestTokens.ISSUER, TestTokens.AUDIENCE, TestTokens.ROLES_CLAIM, TestTokens.ADMIN_ROLE,
            Duration.ofSeconds(60), TestTokens.SECRET);

    private final AuthenticatedUserResolver resolver = new AuthenticatedUserResolver(PROPERTIES);

    @Test
    void shouldResolveCustomerWhenTokenHasNoAdminRole() {
        // When / Then
        StepVerifier.create(resolveWith(token(TestTokens.CUSTOMER.toString(), List.of())))
                .expectNext(new AuthenticatedUser(new CustomerId(TestTokens.CUSTOMER), Boolean.FALSE))
                .verifyComplete();
    }

    @Test
    void shouldResolveAdministratorWhenTokenHasAdminRole() {
        // When / Then
        StepVerifier.create(resolveWith(token(TestTokens.ADMIN.toString(), List.of(ADMIN_AUTHORITY))))
                .expectNext(new AuthenticatedUser(new CustomerId(TestTokens.ADMIN), Boolean.TRUE))
                .verifyComplete();
    }

    @Test
    void shouldFailWithUnauthorizedWhenSubjectIsNotUuid() {
        // When / Then
        StepVerifier.create(resolveWith(token("not-a-uuid", List.of())))
                .expectErrorSatisfies(AuthenticatedUserResolverTest::assertUnauthorized)
                .verify();
    }

    @Test
    void shouldFailWithUnauthorizedWhenAuthenticationIsNotToken() {
        // When / Then
        StepVerifier.create(resolveWith(new TestingAuthenticationToken(TestTokens.CUSTOMER.toString(), null)))
                .expectErrorSatisfies(AuthenticatedUserResolverTest::assertUnauthorized)
                .verify();
    }

    @Test
    void shouldFailWithUnauthorizedWhenThereIsNoSecurityContext() {
        // When / Then
        StepVerifier.create(resolver.resolve())
                .expectErrorSatisfies(AuthenticatedUserResolverTest::assertUnauthorized)
                .verify();
    }

    private Mono<AuthenticatedUser> resolveWith(Authentication authentication) {
        return resolver.resolve().contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    private static JwtAuthenticationToken token(String subject, List<String> authorities) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject(subject).build();
        return new JwtAuthenticationToken(jwt, authorities.stream().map(SimpleGrantedAuthority::new).toList());
    }

    private static void assertUnauthorized(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class);
        assertThat(((TechnicalException) error).getErrorMessage()).isEqualTo(TechnicalErrorMessage.UNAUTHORIZED);
    }
}
