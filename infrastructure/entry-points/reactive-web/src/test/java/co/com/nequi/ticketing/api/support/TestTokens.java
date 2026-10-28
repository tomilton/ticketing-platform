package co.com.nequi.ticketing.api.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Bearer tokens signed with the development secret of the test configuration. Every token is valid
 * unless a customizer changes it.
 */
public final class TestTokens {

    public static final String SECRET = "test-only-secret-for-hs256-tokens-0123456789";
    public static final String ISSUER = "https://issuer.test";
    public static final String AUDIENCE = "ticketing-platform";
    public static final String ROLES_CLAIM = "cognito:groups";
    public static final String ADMIN_ROLE = "ticketing-admin";
    public static final UUID CUSTOMER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final UUID ADMIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Duration LIFETIME = Duration.ofHours(1);

    private TestTokens() {
    }

    public static String customer() {
        return customer(CUSTOMER);
    }

    public static String customer(UUID subject) {
        return token(claims -> claims.subject(subject.toString()));
    }

    public static String admin() {
        return token(claims -> claims.subject(ADMIN.toString()).claim(ROLES_CLAIM, List.of(ADMIN_ROLE)));
    }

    public static String customerWithAdminRole() {
        return token(claims -> claims.claim(ROLES_CLAIM, List.of(ADMIN_ROLE)));
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static String token(UnaryOperator<JWTClaimsSet.Builder> customizer) {
        return sign(customizer.apply(validClaims()).build(), SECRET);
    }

    public static String signedWith(String secret) {
        return sign(validClaims().build(), secret);
    }

    private static String sign(JWTClaimsSet claims, String secret) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException error) {
            throw new IllegalStateException("Test token could not be signed", error);
        }
    }

    private static JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(CUSTOMER.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(LIFETIME)));
    }
}
