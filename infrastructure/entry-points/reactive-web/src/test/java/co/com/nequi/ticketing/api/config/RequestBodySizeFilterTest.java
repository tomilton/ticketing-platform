package co.com.nequi.ticketing.api.config;

import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.support.AuthenticatedClientConfig;
import co.com.nequi.ticketing.api.support.SecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@WebFluxTest
@ContextConfiguration(classes = RequestBodySizeFilterTest.BodySizeTestConfig.class)
class RequestBodySizeFilterTest {

    private static final String PATH = "/api/v1/orders";
    private static final Integer MAXIMUM_BYTES = 16 * 1024;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void shouldRejectWithPayloadTooLargeWhenDeclaredBodyExceedsMaximum() {
        // When / Then
        post("x".repeat(MAXIMUM_BYTES + 1))
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0014");
    }

    @Test
    void shouldLetRequestThroughWhenBodyFitsMaximum() {
        // When / Then
        post("x".repeat(MAXIMUM_BYTES)).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec post(String body) {
        return webTestClient.post().uri(PATH).contentType(MediaType.TEXT_PLAIN).bodyValue(body).exchange();
    }

    @Configuration
    @Import({SecurityTestConfig.class, AuthenticatedClientConfig.class, RequestBodySizeFilter.class,
            GlobalErrorWebExceptionHandler.class, ErrorResponseBuilder.class, ErrorStatusMapper.class})
    static class BodySizeTestConfig {

        @Bean
        RouterFunction<ServerResponse> anyPost() {
            return route(POST(PATH), request -> ServerResponse.ok().build());
        }
    }
}
