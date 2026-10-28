package co.com.nequi.ticketing.api.security;

import co.com.nequi.ticketing.api.support.TestTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Research R-02 of feature 004: the deployments verify the tokens with the JWKS of the identity
 * provider, and the development secret is accepted only with the {@code local} profile.
 */
class JwtDecoderConfigTest {

    private static final String JWKS_PATH = "/jwks";
    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "ticketing-platform";
    private static final String SUBJECT = UUID.randomUUID().toString();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static RSAKey signingKey;
    private static DisposableServer jwksServer;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtDecoderConfig.class)
            .withPropertyValues("security.jwt.issuer=" + ISSUER, "security.jwt.audience=" + AUDIENCE,
                    "security.jwt.roles-claim=cognito:groups", "security.jwt.admin-role=ticketing-admin",
                    "security.jwt.clock-skew=60s");

    @BeforeAll
    static void startJwksServer() throws JOSEException {
        signingKey = new RSAKeyGenerator(RSAKeyGenerator.MIN_KEY_SIZE_BITS).keyID("test-key").generate();
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        jwksServer = HttpServer.create().host("localhost").port(0)
                .route(routes -> routes.get(JWKS_PATH, (request, response) -> response
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just(jwks))))
                .bindNow();
    }

    @AfterAll
    static void stopJwksServer() {
        jwksServer.disposeNow();
    }

    @Test
    void shouldDecodeTokenSignedByIdentityProviderWhenModeIsJwks() {
        runner.withPropertyValues(jwksProperties()).run(context -> {
            // When
            Mono<Jwt> decoded = context.getBean(ReactiveJwtDecoder.class).decode(rs256Token(AUDIENCE));

            // Then
            StepVerifier.create(decoded)
                    .assertNext(jwt -> assertThat(jwt.getSubject()).isEqualTo(SUBJECT))
                    .expectComplete()
                    .verify(TIMEOUT);
        });
    }

    @Test
    void shouldRejectTokenForAnotherAudienceWhenModeIsJwks() {
        runner.withPropertyValues(jwksProperties()).run(context -> {
            // When
            Mono<Jwt> decoded = context.getBean(ReactiveJwtDecoder.class).decode(rs256Token("other-api"));

            // Then
            StepVerifier.create(decoded)
                    .expectError(JwtValidationException.class)
                    .verify(TIMEOUT);
        });
    }

    @Test
    void shouldFailToStartWhenLocalModeIsUsedWithoutLocalProfile() {
        runner.withPropertyValues(localProperties()).run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("only with the local profile"));
    }

    @Test
    void shouldFailToStartWhenLocalModeHasNoSecret() {
        runner.withPropertyValues("spring.profiles.active=local", "security.jwt.mode=LOCAL_HS256",
                        "security.jwt.jwk-set-uri=http://localhost/unused")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("local-secret is required"));
    }

    @Test
    void shouldDecodeDevelopmentTokenWhenLocalModeRunsWithLocalProfile() {
        runner.withPropertyValues(localProperties()).withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    // When
                    Mono<Jwt> decoded = context.getBean(ReactiveJwtDecoder.class).decode(TestTokens.customer());

                    // Then
                    StepVerifier.create(decoded)
                            .assertNext(jwt -> assertThat(jwt.getSubject()).isEqualTo(TestTokens.CUSTOMER.toString()))
                            .expectComplete()
                            .verify(TIMEOUT);
                });
    }

    private static String[] jwksProperties() {
        return new String[]{"security.jwt.mode=JWKS",
                "security.jwt.jwk-set-uri=http://localhost:" + jwksServer.port() + JWKS_PATH};
    }

    private static String[] localProperties() {
        return new String[]{"security.jwt.mode=LOCAL_HS256", "security.jwt.jwk-set-uri=http://localhost/unused",
                "security.jwt.local-secret=" + TestTokens.SECRET};
    }

    private static String rs256Token(String audience) throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(ISSUER).audience(audience).subject(SUBJECT)
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
