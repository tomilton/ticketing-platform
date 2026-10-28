package co.com.nequi.ticketing.api.availability;

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
import co.com.nequi.ticketing.api.support.SecurityTestConfig;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.request.BatchAvailabilityQuery;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.createevent.CreateEventUseCase;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getbatchavailability.GetBatchAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getevent.GetEventUseCase;
import co.com.nequi.ticketing.usecase.listactiveevents.ListActiveEventsUseCase;
import co.com.nequi.ticketing.usecase.watchavailability.WatchAvailabilityUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = AvailabilityRouteIntegrationTest.AvailabilityRouteTestConfig.class)
class AvailabilityRouteIntegrationTest {

    private static final String EVENTS = "/api/v1/events";
    private static final EventId FIRST_ID = new EventId(UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f"));
    private static final EventId SECOND_ID = new EventId(UUID.fromString("7b2d4c6e-8f0a-4b1c-9d2e-3f4a5b6c7d8e"));
    private static final EventId MISSING_ID = new EventId(UUID.fromString("9d8e7f6a-5b4c-4d3e-8f2a-1b0c9d8e7f6a"));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GetAvailabilityUseCase getAvailabilityUseCase;

    @MockitoBean
    private GetBatchAvailabilityUseCase getBatchAvailabilityUseCase;

    @MockitoBean
    private GetEventUseCase getEventUseCase;

    @MockitoBean
    private CreateEventUseCase createEventUseCase;

    @MockitoBean
    private ListActiveEventsUseCase listActiveEventsUseCase;

    @MockitoBean
    private WatchAvailabilityUseCase watchAvailabilityUseCase;

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

    @Test
    void shouldPassRequestIdsAndKeepUseCaseOrderWhenBatchIsRequested() {
        // Given
        BatchAvailabilityQuery query = new BatchAvailabilityQuery(List.of(FIRST_ID, FIRST_ID, MISSING_ID, SECOND_ID));
        when(getBatchAvailabilityUseCase.get(query)).thenReturn(Flux.just(
                new EventInventory(FIRST_ID, 10, 10, 0, 0, 0), new EventInventory(SECOND_ID, 5, 4, 1, 0, 0)));
        String ids = String.join(",", FIRST_ID.value().toString(), FIRST_ID.value().toString(),
                MISSING_ID.value().toString(), SECOND_ID.value().toString());

        // When / Then
        webTestClient.get().uri(EVENTS + "/availability?ids=" + ids).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].eventId").isEqualTo(FIRST_ID.value().toString())
                .jsonPath("$.data[1].eventId").isEqualTo(SECOND_ID.value().toString());
        verifyNoInteractions(getEventUseCase);
    }

    @Test
    void shouldReturnEmptyListWhenNoRequestedEventExists() {
        // Given
        when(getBatchAvailabilityUseCase.get(any(BatchAvailabilityQuery.class))).thenReturn(Flux.empty());

        // When / Then
        webTestClient.get().uri(EVENTS + "/availability?ids=" + MISSING_ID.value()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?ids=", "", "?ids=no-es-uuid"})
    void shouldRejectNamingIdsWhenIdsAreMissingOrInvalid(String query) {
        // When / Then
        assertErrorNaming(webTestClient.get().uri(EVENTS + "/availability" + query).exchange(), "ids");
        verifyNoInteractions(getBatchAvailabilityUseCase, getEventUseCase);
    }

    @Test
    void shouldRejectNamingIdsWhenThereAreMoreThanOneHundredIds() {
        // Given
        String ids = String.join(",", Collections.nCopies(101, FIRST_ID.value().toString()));

        // When / Then
        assertErrorNaming(webTestClient.get().uri(EVENTS + "/availability?ids=" + ids).exchange(), "ids");
    }

    private static void assertErrorNaming(WebTestClient.ResponseSpec response, String parameter) {
        response.expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0001")
                .jsonPath("$.errors[0].message").value(message -> assertThat((String) message).contains(parameter));
    }

    @Configuration
    @EnableConfigurationProperties({RouteProperties.class, EventPaginationProperties.class,
            AvailabilityBatchProperties.class, AvailabilityStreamProperties.class})
    @Import({SecurityTestConfig.class, AvailabilityRouterRest.class, AvailabilityHandler.class, AvailabilityStreamHub.class,
            AvailabilityMapperImpl.class,
            AvailabilityMapManagement.class, EventRouterRest.class, EventHandler.class, EventMapperImpl.class,
            EventMapManagement.class, BeanValidator.class, GlobalErrorWebExceptionHandler.class,
            ErrorResponseBuilder.class, ErrorStatusMapper.class})
    static class AvailabilityRouteTestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
