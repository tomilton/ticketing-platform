package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapManagement;
import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapperImpl;
import co.com.nequi.ticketing.api.config.EventPaginationProperties;
import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.event.EventHandler;
import co.com.nequi.ticketing.api.event.EventRouterRest;
import co.com.nequi.ticketing.api.event.mapper.EventMapManagement;
import co.com.nequi.ticketing.api.event.mapper.EventMapperImpl;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.createevent.CreateEventUseCase;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getevent.GetEventUseCase;
import co.com.nequi.ticketing.usecase.listactiveevents.ListActiveEventsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = AvailabilityRouteIntegrationTest.AvailabilityRouteTestConfig.class)
class AvailabilityRouteIntegrationTest {

    private static final String EVENTS = "/api/v1/events";
    private static final EventId FIRST_ID = new EventId(UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f"));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GetAvailabilityUseCase getAvailabilityUseCase;

    @MockitoBean
    private GetEventUseCase getEventUseCase;

    @MockitoBean
    private CreateEventUseCase createEventUseCase;

    @MockitoBean
    private ListActiveEventsUseCase listActiveEventsUseCase;

    @Test
    void shouldReturnCountersWhenEventExists() {
        // Given
        when(getAvailabilityUseCase.get(FIRST_ID)).thenReturn(Mono.just(new EventInventory(FIRST_ID, 500, 490, 6, 3, 1)));

        // When / Then
        webTestClient.get().uri(EVENTS + "/" + FIRST_ID.value() + "/availability").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.meta.code").isEqualTo("TKS0001")
                .jsonPath("$.data.eventId").isEqualTo(FIRST_ID.value().toString())
                .jsonPath("$.data.capacity").isEqualTo(500)
                .jsonPath("$.data.available").isEqualTo(490)
                .jsonPath("$.data.reserved").isEqualTo(6)
                .jsonPath("$.data.sold").isEqualTo(3)
                .jsonPath("$.data.complimentary").isEqualTo(1);
    }

    @Test
    void shouldAnswerNotFoundWhenEventHasNoInventory() {
        // Given
        when(getAvailabilityUseCase.get(FIRST_ID)).thenReturn(Mono.error(BusinessErrorMessage.EVENT_NOT_FOUND.build()));

        // When / Then
        webTestClient.get().uri(EVENTS + "/" + FIRST_ID.value() + "/availability").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("TKB0001");
    }

    @Test
    void shouldRejectNamingEventIdWhenIdIsNotUuid() {
        // When / Then
        assertErrorNaming(webTestClient.get().uri(EVENTS + "/no-es-uuid/availability").exchange(), "eventId");
        verifyNoInteractions(getAvailabilityUseCase);
    }

    private static void assertErrorNaming(WebTestClient.ResponseSpec response, String parameter) {
        response.expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0001")
                .jsonPath("$.errors[0].message").value(message -> assertThat((String) message).contains(parameter));
    }

    @Configuration
    @EnableConfigurationProperties({RouteProperties.class, EventPaginationProperties.class})
    @Import({AvailabilityRouterRest.class, AvailabilityHandler.class, AvailabilityMapperImpl.class,
            AvailabilityMapManagement.class, EventRouterRest.class, EventHandler.class, EventMapperImpl.class,
            EventMapManagement.class, BeanValidator.class, GlobalErrorWebExceptionHandler.class,
            ErrorResponseBuilder.class, ErrorStatusMapper.class})
    static class AvailabilityRouteTestConfig {
    }
}
