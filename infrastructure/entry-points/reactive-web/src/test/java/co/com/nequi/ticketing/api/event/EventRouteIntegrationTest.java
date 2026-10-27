package co.com.nequi.ticketing.api.event;

import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.event.mapper.EventMapManagement;
import co.com.nequi.ticketing.api.event.mapper.EventMapperImpl;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.Venue;
import co.com.nequi.ticketing.model.event.command.CreateEventCommand;
import co.com.nequi.ticketing.model.shared.Capacity;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.createevent.CreateEventUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = EventRouteIntegrationTest.EventRouteTestConfig.class)
class EventRouteIntegrationTest {

    private static final String EVENTS = "/api/v1/events";
    private static final EventId EVENT_ID = new EventId(UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f"));
    private static final Instant DATE = Instant.parse("2027-03-20T01:00:00Z");
    private static final Event EVENT = Event.create(EVENT_ID, "Rock al Parque", new Venue("Movistar Arena", "Bogota"),
            DATE, new Capacity(500), 1);
    private static final String BODY_TEMPLATE =
            "{\"name\":\"%s\",\"venue\":{\"name\":\"%s\",\"city\":\"%s\"},\"date\":\"%s\",\"capacity\":%s}";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CreateEventUseCase createEventUseCase;

    @Test
    void shouldCreateEventWhenRequestIsValid() {
        // Given
        when(createEventUseCase.create(new CreateEventCommand("Rock al Parque", "Movistar Arena", "Bogota", DATE, 500)))
                .thenReturn(Mono.just(EVENT));

        // When / Then
        post(body("Rock al Parque", "Movistar Arena", "Bogota", DATE.toString(), "500"))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.meta.code").isEqualTo("TKS0002")
                .jsonPath("$.data.eventId").isEqualTo(EVENT_ID.value().toString())
                .jsonPath("$.data.status").isEqualTo("ACTIVE")
                .jsonPath("$.data.venue.city").isEqualTo("Bogota")
                .jsonPath("$.data.capacity").isEqualTo(500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1000001"})
    void shouldRejectWithValidationErrorWhenCapacityIsOutOfRange(String capacity) {
        // When / Then
        assertError(post(body("Rock", "Arena", "Bogota", DATE.toString(), capacity)), 400, "TKT0001");
        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void shouldRejectWithValidationErrorWhenTextFieldsBreakTheirLimits() {
        // When / Then
        assertError(post(body("a".repeat(201), "Arena", "Bogota", DATE.toString(), "1")), 400, "TKT0001");
        assertError(post(body("Rock", " ", "Bogota", DATE.toString(), "1")), 400, "TKT0001");
        assertError(post(body("Rock", "Arena", " ", DATE.toString(), "1")), 400, "TKT0001");
        assertError(post(body("Rock", "a".repeat(101), "Bogota", DATE.toString(), "1")), 400, "TKT0001");
        verifyNoInteractions(createEventUseCase);
    }

    @Test
    void shouldRejectWithEmptyBodyErrorWhenBodyIsMissing() {
        // When / Then
        assertError(webTestClient.post().uri(EVENTS).contentType(MediaType.APPLICATION_JSON).exchange(), 400, "TKT0003");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":", "{\"name\":\"Rock\",\"venue\":{\"name\":\"A\",\"city\":\"B\"},\"date\":\"2027-03-20T01:00:00\",\"capacity\":1}"})
    void shouldRejectWithInvalidJsonErrorWhenBodyCannotBeRead(String body) {
        // When / Then
        assertError(post(body), 400, "TKT0002");
    }

    @Test
    void shouldAnswerUnprocessableWhenDateIsNotInFuture() {
        // Given
        when(createEventUseCase.create(any(CreateEventCommand.class)))
                .thenReturn(Mono.error(BusinessErrorMessage.EVENT_DATE_NOT_IN_FUTURE.build()));

        // When / Then
        assertError(post(body("Rock", "Arena", "Bogota", DATE.toString(), "1")), 422, "TKB0010");
    }

    private WebTestClient.ResponseSpec post(String body) {
        return webTestClient.post().uri(EVENTS).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static String body(String name, String venueName, String city, String date, String capacity) {
        return BODY_TEMPLATE.formatted(name, venueName, city, date, capacity);
    }

    private static void assertError(WebTestClient.ResponseSpec response, Integer status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo(code);
    }

    @Configuration
    @EnableConfigurationProperties(RouteProperties.class)
    @Import({EventRouterRest.class, EventHandler.class, EventMapperImpl.class, EventMapManagement.class,
            BeanValidator.class, GlobalErrorWebExceptionHandler.class, ErrorResponseBuilder.class,
            ErrorStatusMapper.class})
    static class EventRouteTestConfig {
    }
}
