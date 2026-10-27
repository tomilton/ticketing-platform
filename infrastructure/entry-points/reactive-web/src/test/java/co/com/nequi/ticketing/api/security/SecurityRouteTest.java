package co.com.nequi.ticketing.api.security;

import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.support.SecurityTestConfig;
import co.com.nequi.ticketing.api.support.TestTokens;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;

@WebFluxTest
@ContextConfiguration(classes = SecurityRouteTest.SecurityRouteTestConfig.class)
class SecurityRouteTest {

    private static final String EVENT = "b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f";
    private static final String ORDER = "0b8f2c1e-7d33-4c84-b1f2-2a0d8e5c9f10";
    private static final Duration EXPIRED_SINCE = Duration.ofMinutes(5);

    @Autowired
    private WebTestClient webTestClient;

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/events",
            "GET, /api/v1/events/" + EVENT,
            "GET, /api/v1/events/" + EVENT + "/availability",
            "GET, /api/v1/events/availability?ids=" + EVENT,
            "GET, /api/v1/events/" + EVENT + "/availability/stream",
            "GET, /actuator/health"})
    void shouldLetRequestThroughWithoutTokenWhenRouteIsPublic(HttpMethod method, String path) {
        // When / Then
        webTestClient.method(method).uri(path)
                .exchange()
                .expectStatus().isOk();
    }

    @ParameterizedTest
    @MethodSource("protectedRoutes")
    void shouldRejectWithUnauthorizedWhenProtectedRouteHasNoToken(HttpMethod method, String path) {
        // When / Then
        assertUnauthorized(webTestClient.method(method).uri(path).exchange());
    }

    @ParameterizedTest
    @MethodSource("protectedRoutesWithInvalidTokens")
    void shouldRejectWithUnauthorizedWhenProtectedRouteHasInvalidToken(HttpMethod method, String path,
                                                                      String authorization) {
        // When / Then
        assertUnauthorized(webTestClient.method(method).uri(path)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange());
    }

    @ParameterizedTest
    @MethodSource("protectedRoutes")
    void shouldLetRequestThroughWhenProtectedRouteHasValidToken(HttpMethod method, String path) {
        // When / Then
        webTestClient.method(method).uri(path)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.admin()))
                .exchange()
                .expectStatus().isOk();
    }

    private static void assertUnauthorized(WebTestClient.ResponseSpec response) {
        response.expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0010");
    }

    private static Stream<Arguments> protectedRoutes() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/orders"),
                Arguments.of(HttpMethod.GET, "/api/v1/orders/" + ORDER),
                Arguments.of(HttpMethod.POST, "/api/v1/events"),
                Arguments.of(HttpMethod.POST, "/api/v1/events/" + EVENT + "/complimentary"));
    }

    private static Stream<Arguments> protectedRoutesWithInvalidTokens() {
        return protectedRoutes().flatMap(route -> invalidAuthorizations()
                .map(authorization -> Arguments.of(route.get()[0], route.get()[1], authorization)));
    }

    private static Stream<String> invalidAuthorizations() {
        Date expired = Date.from(Instant.now().minus(EXPIRED_SINCE));
        return Stream.of(
                TestTokens.bearer("not-a-token"),
                TestTokens.bearer(TestTokens.token(claims -> claims.issueTime(expired).expirationTime(expired))),
                TestTokens.bearer(TestTokens.signedWith("another-secret-for-hs256-tokens-0123456789")),
                TestTokens.bearer(TestTokens.token(claims -> claims.issuer("https://other-issuer.test"))),
                TestTokens.bearer(TestTokens.token(claims -> claims.audience("other-audience"))),
                TestTokens.bearer(TestTokens.token(claims -> claims.subject("not-a-uuid"))));
    }

    @Configuration
    @Import({SecurityTestConfig.class, GlobalErrorWebExceptionHandler.class, ErrorResponseBuilder.class,
            ErrorStatusMapper.class})
    static class SecurityRouteTestConfig {

        @Bean
        RouterFunction<ServerResponse> anyRoute() {
            return RouterFunctions.route(RequestPredicates.all(), request -> ServerResponse.ok().build());
        }
    }
}
