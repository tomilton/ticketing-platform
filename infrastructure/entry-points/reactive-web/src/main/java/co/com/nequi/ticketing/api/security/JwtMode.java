package co.com.nequi.ticketing.api.security;

/**
 * How the bearer tokens are verified.
 */
public enum JwtMode {
    /** With the public keys of the identity provider, read from its JWKS endpoint. */
    JWKS,
    /** With a development secret and HS256; allowed only with the {@code local} profile. */
    LOCAL_HS256
}
