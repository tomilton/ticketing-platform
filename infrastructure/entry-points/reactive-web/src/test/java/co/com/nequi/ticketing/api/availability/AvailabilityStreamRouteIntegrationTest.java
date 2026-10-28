package co.com.nequi.ticketing.api.availability;

import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapManagement;
import co.com.nequi.ticketing.api.availability.mapper.AvailabilityMapperImpl;
import co.com.nequi.ticketing.api.config.AvailabilityBatchProperties;
import co.com.nequi.ticketing.api.config.AvailabilityStreamProperties;
import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.support.SecurityTestConfig;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.getavailability.GetAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.getbatchavailability.GetBatchAvailabilityUseCase;
import co.com.nequi.ticketing.usecase.watchavailability.WatchAvailabilityUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = AvailabilityStreamRouteIntegrationTest.StreamRouteTestConfig.class)
class AvailabilityStreamRouteIntegrationTest {

    private static final EventId EVENT_ID = new EventId(UUID.fromString("3f1c2a7e-1d2b-4c5d-9e8f-0a1b2c3d4e5f"));
    private static final String STREAM = "/api/v1/events/" + EVENT_ID.value() + "/availability/stream";
    private static final EventInventory INVENTORY = new EventInventory(EVENT_ID, 500, 500, 0, 0, 0);
    private static final Duration RETRY = Duration.ofSeconds(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> EVENT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GetAvailabilityUseCase getAvailabilityUseCase;

    @MockitoBean
    private GetBatchAvailabilityUseCase getBatchAvailabilityUseCase;

    @MockitoBean
    private WatchAvailabilityUseCase watchAvailabilityUseCase;

    @Test
    void shouldSendAvailabilityHeartbeatsAndReconnectWhenStreamReachesMaximumDuration() {
        // Given
        when(getAvailabilityUseCase.get(EVENT_ID)).thenReturn(Mono.just(INVENTORY));
        when(watchAvailabilityUseCase.watch(EVENT_ID)).thenReturn(Flux.just(INVENTORY).concatWith(Flux.never()));

        // When / Then
        StepVerifier.create(stream())
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("availability");
                    assertThat(event.retry()).isEqualTo(RETRY);
                    assertThat(event.data()).contains("\"available\":500");
                })
                .thenConsumeWhile(event -> event.event() == null,
                        event -> assertThat(event.comment()).isEqualTo("keepalive"))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("reconnect");
                    assertThat(event.retry()).isEqualTo(RETRY);
                })
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void shouldSendErrorEventAndCloseWhenReadFailsDuringStream() {
        // Given
        when(getAvailabilityUseCase.get(EVENT_ID)).thenReturn(Mono.just(INVENTORY));
        when(watchAvailabilityUseCase.watch(EVENT_ID))
                .thenReturn(Flux.just(INVENTORY).concatWith(Flux.error(DYNAMODB_ERROR.build())));

        // When / Then
        assertStreamEndsWithError("TKT0005");
    }

    @Test
    void shouldSendNotFoundErrorInsteadOfAvailabilityWhenEventDisappearsDuringStream() {
        // Given
        when(getAvailabilityUseCase.get(EVENT_ID)).thenReturn(Mono.just(INVENTORY));
        when(watchAvailabilityUseCase.watch(EVENT_ID))
                .thenReturn(Flux.just(INVENTORY).concatWith(Flux.error(EVENT_NOT_FOUND.build())));

        // When / Then
        assertStreamEndsWithError("TKB0001");
    }

    @Test
    void shouldAnswerJsonNotFoundWithoutStreamWhenEventDoesNotExist() {
        // Given
        when(getAvailabilityUseCase.get(EVENT_ID)).thenReturn(Mono.error(EVENT_NOT_FOUND.build()));

        // When / Then
        webTestClient.get().uri(STREAM).accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("TKB0001");
        verifyNoInteractions(watchAvailabilityUseCase);
    }

    @Test
    void shouldRejectWithValidationErrorWhenIdIsNotUuid() {
        // When / Then
        webTestClient.get().uri("/api/v1/events/no-es-uuid/availability/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].code").isEqualTo("TKT0001");
    }

    private void assertStreamEndsWithError(String code) {
        StepVerifier.create(stream())
                .assertNext(event -> assertThat(event.event()).isEqualTo("availability"))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                    assertThat(event.data()).contains("\"code\":\"" + code + "\"");
                })
                .expectComplete()
                .verify(TIMEOUT);
    }

    private Flux<ServerSentEvent<String>> stream() {
        return webTestClient.get().uri(STREAM).accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .returnResult(EVENT_TYPE)
                .getResponseBody();
    }

    @Configuration
    @EnableConfigurationProperties({RouteProperties.class, AvailabilityBatchProperties.class,
            AvailabilityStreamProperties.class})
    @Import({SecurityTestConfig.class, AvailabilityRouterRest.class, AvailabilityHandler.class, AvailabilityStreamHub.class,
            AvailabilityMapperImpl.class, AvailabilityMapManagement.class, GlobalErrorWebExceptionHandler.class,
            ErrorResponseBuilder.class, ErrorStatusMapper.class})
    static class StreamRouteTestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
