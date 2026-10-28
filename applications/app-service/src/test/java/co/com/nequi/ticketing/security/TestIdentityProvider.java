package co.com.nequi.ticketing.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Identity provider of the integration tests: it publishes a JWKS, as Cognito does, and signs RS256
 * tokens with the issuer, audience and roles claim of the test configuration. The application runs
 * in JWKS mode, the mode of the deployments. The endpoint has its own event loop, because every
 * application context that closes disposes the shared resources of Reactor Netty.
 */
public final class TestIdentityProvider {

    public static final String JWK_SET_URI_PROPERTY = "security.jwt.jwk-set-uri";
    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "ticketing-platform";
    private static final String ROLES_CLAIM = "cognito:groups";
    private static final String ADMIN_ROLE = "ticketing-admin";
    private static final String JWKS_PATH = "/.well-known/jwks.json";
    private static final String LOOP_NAME = "test-identity-provider";
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private static final RSAKey SIGNING_KEY = generateKey();
    private static DisposableServer server;

    private TestIdentityProvider() {
    }

    /**
     * Starts the JWKS endpoint once per JVM.
     *
     * @return the URI of the JWKS
     */
    public static synchronized String jwkSetUri() {
        if (server == null) {
            String jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString();
            server = HttpServer.create().host("localhost").port(0)
                    .runOn(LoopResources.create(LOOP_NAME, 1, true))
                    .route(routes -> routes.get(JWKS_PATH, (request, response) -> response
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .sendString(Mono.just(jwks))))
                    .bindNow();
        }
        return "http://localhost:" + server.port() + JWKS_PATH;
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static String customer(String subject) {
        return sign(claims(subject).build());
    }

    public static String admin() {
        return sign(claims(UUID.randomUUID().toString()).claim(ROLES_CLAIM, List.of(ADMIN_ROLE)).build());
    }

    private static JWTClaimsSet.Builder claims(String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(subject)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(LIFETIME)));
    }

    private static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID())
                    .build(), claims);
            jwt.sign(new RSASSASigner(SIGNING_KEY));
            return jwt.serialize();
        } catch (JOSEException error) {
            throw new IllegalStateException("Test token could not be signed", error);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(RSAKeyGenerator.MIN_KEY_SIZE_BITS).keyID("integration-test").generate();
        } catch (JOSEException error) {
            throw new IllegalStateException("Test key could not be generated", error);
        }
    }
}
