package co.com.nequi.ticketing.api.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Decoder of the bearer tokens. Whatever the mode, a token is valid only with a correct signature,
 * the configured issuer and audience, an expiration and a subject that is a UUID.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtDecoderConfig {

    private static final String LOCAL_PROFILE = "local";
    private static final String HMAC_SHA256 = "HmacSHA256";

    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtProperties properties, Environment environment) {
        NimbusReactiveJwtDecoder decoder = switch (properties.mode()) {
            case JWKS -> NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
            case LOCAL_HS256 -> localDecoder(properties, environment);
        };
        decoder.setJwtValidator(validator(properties));
        return decoder;
    }

    private static NimbusReactiveJwtDecoder localDecoder(JwtProperties properties, Environment environment) {
        if (!environment.matchesProfiles(LOCAL_PROFILE)) {
            throw new IllegalStateException("security.jwt.mode LOCAL_HS256 is allowed only with the local profile");
        }
        String secret = Optional.ofNullable(properties.localSecret()).filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("security.jwt.local-secret is required in LOCAL_HS256 mode"));
        return NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static OAuth2TokenValidator<Jwt> validator(JwtProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(properties.clockSkew()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, JwtDecoderConfig::isUuid));
    }

    private static boolean isUuid(String subject) {
        try {
            return subject != null && UUID.fromString(subject).toString().equalsIgnoreCase(subject);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
