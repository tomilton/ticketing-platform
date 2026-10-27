package co.com.nequi.ticketing.api.handle.exceptions;

import co.com.nequi.ticketing.api.messages.exception.ValidationException;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@WebFluxTest
@ContextConfiguration(classes = {GlobalErrorWebExceptionHandlerTest.FailingRouter.class,
        GlobalErrorWebExceptionHandler.class, ErrorResponseBuilder.class, ErrorStatusMapper.class})
class GlobalErrorWebExceptionHandlerTest {

    @Autowired
    private WebTestClient webTestClient;

    @ParameterizedTest
    @CsvSource({
            "/business, 404, TKB0001, Event not found",
            "/technical, 500, TKT0005, Database error",
            "/validation, 400, TKT0001, limit: must be positive",
            "/unexpected, 500, TKT0009, Unexpected error",
            "/missing, 404, TKT0001, Resource not found"
    })
    void shouldAnswerErrorListWithCodeAndPathWhenRouteFails(String path, Integer status, String code, String message) {
        // When / Then
        webTestClient.get().uri(path)
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].code").isEqualTo(code)
                .jsonPath("$.errors[0].message").isEqualTo(message)
                .jsonPath("$.errors[0].reason").isNotEmpty()
                .jsonPath("$.errors[0].domain").isEqualTo(path);
    }

    @Configuration
    static class FailingRouter {

        @Bean
        RouterFunction<ServerResponse> failingRoutes() {
            return route(GET("/business"), request -> Mono.error(BusinessErrorMessage.EVENT_NOT_FOUND.build()))
                    .andRoute(GET("/technical"), request -> Mono.error(TechnicalErrorMessage.DYNAMODB_ERROR.build()))
                    .andRoute(GET("/validation"), request -> Mono.error(new ValidationException("limit", "must be positive")))
                    .andRoute(GET("/unexpected"), request -> Mono.error(new IllegalStateException("boom")));
        }
    }
}
