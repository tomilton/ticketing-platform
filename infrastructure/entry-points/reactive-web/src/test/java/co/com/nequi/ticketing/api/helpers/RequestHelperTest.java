package co.com.nequi.ticketing.api.helpers;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHelperTest {

    @Test
    void shouldReadBodyWhenJsonIsValid() {
        // Given
        ServerRequest request = request("{\"name\":\"Rock\",\"date\":\"2026-12-01T20:00:00Z\"}");

        // When / Then
        StepVerifier.create(RequestHelper.bodyToMonoOrFailWhenEmpty(request, SampleDto.class))
                .expectNext(new SampleDto("Rock", Instant.parse("2026-12-01T20:00:00Z")))
                .verifyComplete();
    }

    @Test
    void shouldFailWithEmptyRequestBodyWhenBodyIsEmpty() {
        // When / Then
        StepVerifier.create(RequestHelper.bodyToMonoOrFailWhenEmpty(requestWithoutBody(), SampleDto.class))
                .expectErrorSatisfies(error -> assertTechnicalError(error, TechnicalErrorMessage.EMPTY_REQUEST_BODY))
                .verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":", "{\"name\":\"Rock\",\"date\":\"2026-12-01T20:00:00\"}"})
    void shouldFailWithInvalidJsonWhenBodyCannotBeRead(String body) {
        // When / Then
        StepVerifier.create(RequestHelper.bodyToMonoOrFailWhenEmpty(request(body), SampleDto.class))
                .expectErrorSatisfies(error -> assertTechnicalError(error, TechnicalErrorMessage.INVALID_JSON))
                .verify();
    }

    private static void assertTechnicalError(Throwable error, TechnicalErrorMessage expected) {
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(expected);
    }

    private static ServerRequest request(String body) {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        return ServerRequest.create(MockServerWebExchange.from(httpRequest),
                HandlerStrategies.withDefaults().messageReaders());
    }

    private static ServerRequest requestWithoutBody() {
        MockServerHttpRequest httpRequest = MockServerHttpRequest.post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .build();
        return ServerRequest.create(MockServerWebExchange.from(httpRequest),
                HandlerStrategies.withDefaults().messageReaders());
    }

    record SampleDto(String name, Instant date) {
    }
}
