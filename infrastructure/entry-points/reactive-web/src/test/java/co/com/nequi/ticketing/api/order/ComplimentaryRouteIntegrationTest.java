package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.config.JsonMapperConfig;
import co.com.nequi.ticketing.api.config.RouteProperties;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorResponseBuilder;
import co.com.nequi.ticketing.api.handle.exceptions.ErrorStatusMapper;
import co.com.nequi.ticketing.api.handle.exceptions.GlobalErrorWebExceptionHandler;
import co.com.nequi.ticketing.api.order.mapper.OrderMapManagement;
import co.com.nequi.ticketing.api.order.mapper.OrderMapperImpl;
import co.com.nequi.ticketing.api.support.AuthenticatedClientConfig;
import co.com.nequi.ticketing.api.support.SecurityTestConfig;
import co.com.nequi.ticketing.api.validation.BeanValidator;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.command.GrantComplimentaryCommand;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.grantcomplimentary.GrantComplimentaryUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = ComplimentaryRouteIntegrationTest.ComplimentaryRouteTestConfig.class)
class ComplimentaryRouteIntegrationTest {

    private static final String CUSTOMER = "11111111-1111-4111-8111-111111111111";
    private static final String EVENT = "b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f";
    private static final String PATH = "/api/v1/events/" + EVENT + "/complimentary";
    private static final GrantComplimentaryCommand COMMAND = new GrantComplimentaryCommand(
            new EventId(UUID.fromString(EVENT)), new CustomerId(UUID.fromString(CUSTOMER)), new ComplimentaryQuantity(3));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GrantComplimentaryUseCase grantComplimentaryUseCase;

    @Test
    void shouldCreateComplimentaryOrderWhenRequestIsValid() {
        // Given
        Order order = Order.grantComplimentary(OrderId.newId(), COMMAND.customerId(), COMMAND.eventId(),
                COMMAND.quantity(), 0, Instant.parse("2026-10-27T10:00:00Z"));
        when(grantComplimentaryUseCase.grant(COMMAND)).thenReturn(Mono.just(order));

        // When / Then
        post(PATH, body(CUSTOMER, "3"))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.meta.code").isEqualTo("TKS0002")
                .jsonPath("$.data.orderId").isEqualTo(order.getOrderId().value().toString())
                .jsonPath("$.data.status").isEqualTo("COMPLIMENTARY")
                .jsonPath("$.data.quantity").isEqualTo(3)
                .jsonPath("$.data.tickets.length()").isEqualTo(3)
                .jsonPath("$.data.tickets[0].status").isEqualTo("COMPLIMENTARY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "2.5", "null"})
    void shouldRejectNamingQuantityWhenQuantityIsInvalid(String quantity) {
        // When / Then
        assertErrorNaming(post(PATH, body(CUSTOMER, quantity)), "quantity");
        verifyNoInteractions(grantComplimentaryUseCase);
    }

    @Test
    void shouldRejectNamingCustomerIdWhenCustomerIdIsMissing() {
        // When / Then
        assertErrorNaming(post(PATH, "{\"quantity\":3}"), "customerId");
        verifyNoInteractions(grantComplimentaryUseCase);
    }

    @Test
    void shouldRejectNamingCustomerIdWhenCustomerIdIsNotUuid() {
        // When / Then
        assertErrorNaming(post(PATH, "{\"customerId\":\"abc\",\"quantity\":3}"), "customerId");
        verifyNoInteractions(grantComplimentaryUseCase);
    }

    @Test
    void shouldRejectNamingEventIdWhenEventIdIsNotUuid() {
        // When / Then
        assertErrorNaming(post("/api/v1/events/not-a-uuid/complimentary", body(CUSTOMER, "3")), "eventId");
        verifyNoInteractions(grantComplimentaryUseCase);
    }

    @Test
    void shouldRejectWithEmptyBodyErrorWhenBodyIsMissing() {
        // When / Then
        assertError(webTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).exchange(), 400, "TKT0003");
        verifyNoInteractions(grantComplimentaryUseCase);
    }

    @ParameterizedTest
    @CsvSource({
            "EVENT_NOT_FOUND, 404, TKB0001",
            "TICKETS_SOLD_OUT, 409, TKB0003",
            "EVENT_NOT_ACTIVE, 409, TKB0007"})
    void shouldAnswerRejectionWithItsStatusAndCodeWhenUseCaseRejects(BusinessErrorMessage rejection, Integer status,
                                                                     String code) {
        // Given
        when(grantComplimentaryUseCase.grant(COMMAND)).thenReturn(Mono.error(rejection.build()));

        // When / Then
        assertError(post(PATH, body(CUSTOMER, "3")), status, code);
    }

    private WebTestClient.ResponseSpec post(String path, String body) {
        return webTestClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static String body(String customer, String quantity) {
        return "{\"customerId\":\"" + customer + "\",\"quantity\":" + quantity + "}";
    }

    private static void assertError(WebTestClient.ResponseSpec response, Integer status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo(code);
    }

    private static void assertErrorNaming(WebTestClient.ResponseSpec response, String field) {
        response.expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0001")
                .jsonPath("$.errors[0].message").value(message -> assertThat((String) message).contains(field));
    }

    @Configuration
    @EnableConfigurationProperties(RouteProperties.class)
    @Import({SecurityTestConfig.class, AuthenticatedClientConfig.class, ComplimentaryRouterRest.class, ComplimentaryHandler.class, OrderMapperImpl.class, OrderMapManagement.class,
            BeanValidator.class, GlobalErrorWebExceptionHandler.class, ErrorResponseBuilder.class,
            ErrorStatusMapper.class, JsonMapperConfig.class})
    static class ComplimentaryRouteTestConfig {
    }
}
