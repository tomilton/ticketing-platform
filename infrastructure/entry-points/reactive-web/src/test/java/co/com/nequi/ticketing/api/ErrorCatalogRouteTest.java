package co.com.nequi.ticketing.api;

import co.com.nequi.ticketing.api.availability.AvailabilityHandler;
import co.com.nequi.ticketing.api.availability.AvailabilityRouterRest;
import co.com.nequi.ticketing.api.availability.AvailabilityStreamHub;
import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapManagement;
import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapperImpl;
import co.com.nequi.ticketing.api.config.AvailabilityBatchProperties;
import co.com.nequi.ticketing.api.config.AvailabilityStreamProperties;
import co.com.nequi.ticketing.api.config.EventPaginationProperties;
import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.event.EventHandler;
import co.com.nequi.ticketing.api.event.EventRouterRest;
import co.com.nequi.ticketing.api.event.mapper.EventMapManagement;
import co.com.nequi.ticketing.api.event.mapper.EventMapperImpl;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.order.ComplimentaryHandler;
import co.com.nequi.ticketing.api.order.ComplimentaryRouterRest;
import co.com.nequi.ticketing.api.order.mapper.OrderMapManagement;
import co.com.nequi.ticketing.api.order.mapper.OrderMapperImpl;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.model.event.command.CreateEventCommand;
import co.com.nequi.ticketing.model.order.command.GrantComplimentaryCommand;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.createevent.CreateEventUseCase;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getbatchavailability.GetBatchAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getevent.GetEventUseCase;
import co.com.nequi.ticketing.usecase.grantcomplimentary.GrantComplimentaryUseCase;
import co.com.nequi.ticketing.usecase.listactiveevents.ListActiveEventsUseCase;
import co.com.nequi.ticketing.usecase.watchavailability.WatchAvailabilityUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_ACTIVE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKETS_SOLD_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SC-004 of feature 001 and SC-005 of feature 003: every error case of the specification answers
 * with its HTTP status and an exact catalog code.
 */
@WebFluxTest
@ContextConfiguration(classes = ErrorCatalogRouteTest.ErrorCatalogTestConfig.class)
class ErrorCatalogRouteTest {

    private static final String EVENTS = "/api/v1/events";
    private static final String KNOWN_ID = "3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f";
    private static final String CODE_PATTERN = "^TK[BT]\\d{4}$";
    private static final String VALID_BODY =
            "{\"name\":\"Rock\",\"venue\":{\"name\":\"Arena\",\"city\":\"Bogota\"},\"date\":\"2020-01-01T00:00:00Z\",\"capacity\":1}";
    private static final String COMPLIMENTARY = EVENTS + "/" + KNOWN_ID + "/complimentary";
    private static final String COMPLIMENTARY_BODY = "{\"customerId\":\"" + KNOWN_ID + "\",\"quantity\":3}";
    private static final Consumer<ErrorCatalogRouteTest> NO_STUB = test -> {
    };

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CreateEventUseCase createEventUseCase;

    @MockitoBean
    private GetEventUseCase getEventUseCase;

    @MockitoBean
    private ListActiveEventsUseCase listActiveEventsUseCase;

    @MockitoBean
    private GetAvailabilityUseCase getAvailabilityUseCase;

    @MockitoBean
    private GetBatchAvailabilityUseCase getBatchAvailabilityUseCase;

    @MockitoBean
    private WatchAvailabilityUseCase watchAvailabilityUseCase;

    @MockitoBean
    private GrantComplimentaryUseCase grantComplimentaryUseCase;

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorCases")
    void shouldAnswerCatalogCodeWhenSpecificationErrorHappens(String scenario, HttpMethod method, String uri,
                                                              String body, Integer status, String code,
                                                              Consumer<ErrorCatalogRouteTest> stub) {
        // Given
        stub.accept(this);
        WebTestClient.RequestBodySpec request = webTestClient.method(method).uri(uri)
                .contentType(MediaType.APPLICATION_JSON);

        // When
        WebTestClient.ResponseSpec response = body == null ? request.exchange() : request.bodyValue(body).exchange();

        // Then
        response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo(code)
                .jsonPath("$.errors[0].code").value(value -> assertThat((String) value).matches(CODE_PATTERN));
    }

    private static Stream<Arguments> errorCases() {
        return Stream.of(
                Arguments.of("US1-2 capacity zero", HttpMethod.POST, EVENTS, VALID_BODY.replace("\"capacity\":1",
                        "\"capacity\":0"), 400, "TKT0001", NO_STUB),
                Arguments.of("US1-3 date not in future", HttpMethod.POST, EVENTS, VALID_BODY, 422, "TKB0010",
                        (Consumer<ErrorCatalogRouteTest>) test -> when(test.createEventUseCase.create(any(CreateEventCommand.class)))
                                .thenReturn(Mono.error(EVENT_DATE_NOT_IN_FUTURE.build()))),
                Arguments.of("US1-4 blank city", HttpMethod.POST, EVENTS, VALID_BODY.replace("Bogota", " "), 400,
                        "TKT0001", NO_STUB),
                Arguments.of("US1-5 empty body", HttpMethod.POST, EVENTS, null, 400, "TKT0003", NO_STUB),
                Arguments.of("US1-5 malformed body", HttpMethod.POST, EVENTS, "{\"name\":", 400, "TKT0002", NO_STUB),
                Arguments.of("US2-2 event not found", HttpMethod.GET, EVENTS + "/" + KNOWN_ID, null, 404, "TKB0001",
                        (Consumer<ErrorCatalogRouteTest>) test -> when(test.getEventUseCase.get(any(EventId.class)))
                                .thenReturn(Mono.error(EVENT_NOT_FOUND.build()))),
                Arguments.of("US2-3 invalid id", HttpMethod.GET, EVENTS + "/no-es-uuid", null, 400, "TKT0001", NO_STUB),
                Arguments.of("US3-3 availability not found", HttpMethod.GET, EVENTS + "/" + KNOWN_ID + "/availability",
                        null, 404, "TKB0001",
                        (Consumer<ErrorCatalogRouteTest>) test -> when(test.getAvailabilityUseCase.get(any(EventId.class)))
                                .thenReturn(Mono.error(EVENT_NOT_FOUND.build()))),
                Arguments.of("US4-5 invalid limit", HttpMethod.GET, EVENTS + "?limit=0", null, 400, "TKT0001", NO_STUB),
                Arguments.of("US5-2 more than 100 ids", HttpMethod.GET, EVENTS + "/availability?ids="
                        + String.join(",", Collections.nCopies(101, KNOWN_ID)), null, 400, "TKT0001", NO_STUB),
                Arguments.of("US5-3 invalid id in batch", HttpMethod.GET, EVENTS + "/availability?ids=no-es-uuid",
                        null, 400, "TKT0001", NO_STUB),
                Arguments.of("US6-5 stream of missing event", HttpMethod.GET,
                        EVENTS + "/" + KNOWN_ID + "/availability/stream", null, 404, "TKB0001",
                        (Consumer<ErrorCatalogRouteTest>) test -> when(test.getAvailabilityUseCase.get(any(EventId.class)))
                                .thenReturn(Mono.error(EVENT_NOT_FOUND.build()))),
                Arguments.of("003 US2-2 complimentary sold out", HttpMethod.POST, COMPLIMENTARY, COMPLIMENTARY_BODY,
                        409, "TKB0003", grantFailingWith(TICKETS_SOLD_OUT.build())),
                Arguments.of("003 US2-3 complimentary event not found", HttpMethod.POST, COMPLIMENTARY,
                        COMPLIMENTARY_BODY, 404, "TKB0001", grantFailingWith(EVENT_NOT_FOUND.build())),
                Arguments.of("003 US2-3 complimentary event not on sale", HttpMethod.POST, COMPLIMENTARY,
                        COMPLIMENTARY_BODY, 409, "TKB0007", grantFailingWith(EVENT_NOT_ACTIVE.build())),
                Arguments.of("003 US2-4 complimentary quantity zero", HttpMethod.POST, COMPLIMENTARY,
                        COMPLIMENTARY_BODY.replace("3}", "0}"), 400, "TKT0001", NO_STUB),
                Arguments.of("003 US2-4 complimentary quantity above 50", HttpMethod.POST, COMPLIMENTARY,
                        COMPLIMENTARY_BODY.replace("3}", "51}"), 400, "TKT0001", NO_STUB),
                Arguments.of("003 US2-4 complimentary customer missing", HttpMethod.POST, COMPLIMENTARY,
                        "{\"quantity\":3}", 400, "TKT0001", NO_STUB),
                Arguments.of("003 US2-4 complimentary customer not UUID", HttpMethod.POST, COMPLIMENTARY,
                        COMPLIMENTARY_BODY.replace(KNOWN_ID, "abc"), 400, "TKT0001", NO_STUB),
                Arguments.of("003 US2-4 complimentary event id not UUID", HttpMethod.POST,
                        EVENTS + "/no-es-uuid/complimentary", COMPLIMENTARY_BODY, 400, "TKT0001", NO_STUB),
                Arguments.of("003 complimentary empty body", HttpMethod.POST, COMPLIMENTARY, null, 400, "TKT0003",
                        NO_STUB),
                Arguments.of("003 complimentary unexpected error", HttpMethod.POST, COMPLIMENTARY, COMPLIMENTARY_BODY,
                        500, "TKT0009", grantFailingWith(new IllegalStateException("unexpected"))),
                Arguments.of("004 unauthorized", HttpMethod.POST, COMPLIMENTARY, COMPLIMENTARY_BODY, 401, "TKT0010",
                        grantFailingWith(TechnicalErrorMessage.UNAUTHORIZED.build())),
                Arguments.of("004 forbidden", HttpMethod.POST, COMPLIMENTARY, COMPLIMENTARY_BODY, 403, "TKT0011",
                        grantFailingWith(TechnicalErrorMessage.FORBIDDEN.build())),
                Arguments.of("004 rate limited", HttpMethod.POST, COMPLIMENTARY, COMPLIMENTARY_BODY, 429, "TKT0012",
                        grantFailingWith(TechnicalErrorMessage.RATE_LIMITED.build())),
                Arguments.of("004 invalid webhook signature", HttpMethod.POST, COMPLIMENTARY, COMPLIMENTARY_BODY, 401,
                        "TKT0013", grantFailingWith(TechnicalErrorMessage.INVALID_WEBHOOK_SIGNATURE.build())),
                Arguments.of("004 payload too large", HttpMethod.POST, COMPLIMENTARY, COMPLIMENTARY_BODY, 413,
                        "TKT0014", grantFailingWith(TechnicalErrorMessage.PAYLOAD_TOO_LARGE.build())),
                Arguments.of("Unexpected error", HttpMethod.GET, EVENTS + "/" + KNOWN_ID, null, 500, "TKT0009",
                        (Consumer<ErrorCatalogRouteTest>) test -> when(test.getEventUseCase.get(any(EventId.class)))
                                .thenReturn(Mono.error(new IllegalStateException("unexpected")))));
    }

    private static Consumer<ErrorCatalogRouteTest> grantFailingWith(RuntimeException error) {
        return test -> when(test.grantComplimentaryUseCase.grant(any(GrantComplimentaryCommand.class)))
                .thenReturn(Mono.error(error));
    }

    @Configuration
    @EnableConfigurationProperties({RouteProperties.class, EventPaginationProperties.class,
            AvailabilityBatchProperties.class, AvailabilityStreamProperties.class})
    @Import({EventRouterRest.class, EventHandler.class, EventMapperImpl.class, EventMapManagement.class,
            AvailabilityRouterRest.class, AvailabilityHandler.class, AvailabilityStreamHub.class,
            AvailabilityMapperImpl.class, AvailabilityMapManagement.class, BeanValidator.class,
            GlobalErrorWebExceptionHandler.class, ErrorResponseBuilder.class, ErrorStatusMapper.class,
            ComplimentaryRouterRest.class, ComplimentaryHandler.class, OrderMapperImpl.class, OrderMapManagement.class})
    static class ErrorCatalogTestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
