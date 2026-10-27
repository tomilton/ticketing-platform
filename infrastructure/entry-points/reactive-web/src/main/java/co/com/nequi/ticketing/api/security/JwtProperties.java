package co.com.nequi.ticketing.api.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Verification of the bearer tokens, read from the {@code security.jwt} block.
 *
 * @param mode        how the signature is verified
 * @param jwkSetUri   the JWKS endpoint of the identity provider, used in {@link JwtMode#JWKS}
 * @param issuer      the expected {@code iss} claim
 * @param audience    the value the {@code aud} claim must contain
 * @param rolesClaim  the claim with the roles of the caller, a list or a text separated by spaces
 * @param adminRole   the role that allows the administrator operations
 * @param clockSkew   the tolerance on {@code exp} and {@code nbf}
 * @param localSecret the development secret of {@link JwtMode#LOCAL_HS256}; only in the local profile
 */
@Validated
@ConfigurationProperties("security.jwt")
public record JwtProperties(@NotNull JwtMode mode,
                            @NotBlank String jwkSetUri,
                            @NotBlank String issuer,
                            @NotBlank String audience,
                            @NotBlank String rolesClaim,
                            @NotBlank String adminRole,
                            @NotNull Duration clockSkew,
                            String localSecret) {
}
