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
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderView;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.request.GetOrderQuery;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = OrderRouteIntegrationTest.OrderRouteTestConfig.class)
class OrderRouteIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String CUSTOMER = "11111111-1111-4111-8111-111111111111";
    private static final String KEY = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
    private static final String EVENT = "b2c4e6f8-1a3b-4c5d-8e9f-0a1b2c3d4e5f";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-27T10:10:00Z");
    private static final PlaceOrderCommand COMMAND = new PlaceOrderCommand(new CustomerId(UUID.fromString(CUSTOMER)),
            new IdempotencyKey(UUID.fromString(KEY)), new EventId(UUID.fromString(EVENT)), new Quantity(2));

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    private GetOrderUseCase getOrderUseCase;

    @Test
    void shouldAcceptOrderWithReservedStatusWhenRequestIsValid() {
        // Given
        OrderId orderId = OrderId.newId();
        when(placeOrderUseCase.place(COMMAND))
                .thenReturn(Mono.just(new PlacementOutcome.Accepted(orderId, OrderStatus.RESERVED, EXPIRES_AT)));

        // When / Then
        post(CUSTOMER, KEY, body(2))
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.meta.code").isEqualTo("TKS0003")
                .jsonPath("$.data.orderId").isEqualTo(orderId.value().toString())
                .jsonPath("$.data.status").isEqualTo("RESERVED")
                .jsonPath("$.data.expiresAt").isEqualTo("2026-10-27T10:10:00Z");
    }

    @ParameterizedTest
    @CsvSource({
            "EVENT_NOT_FOUND, 404, TKB0001",
            "TICKETS_SOLD_OUT, 409, TKB0003",
            "TICKET_LIMIT_EXCEEDED, 422, TKB0004",
            "IDEMPOTENCY_KEY_REUSED, 422, TKB0005",
            "REQUEST_IN_PROGRESS, 409, TKB0006",
            "EVENT_NOT_ACTIVE, 409, TKB0007"})
    void shouldAnswerRejectionWithItsStatusAndCodeWhenUseCaseRejects(BusinessErrorMessage rejection, Integer status,
                                                                     String code) {
        // Given
        when(placeOrderUseCase.place(COMMAND)).thenReturn(Mono.error(rejection.build()));

        // When / Then
        assertError(post(CUSTOMER, KEY, body(2)), status, code);
    }

    @Test
    void shouldRejectWithEmptyBodyErrorWhenBodyIsMissing() {
        // When / Then
        assertError(webTestClient.post().uri(ORDERS).header("X-Customer-Id", CUSTOMER).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).exchange(), 400, "TKT0003");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectWithInvalidJsonErrorWhenBodyIsBroken() {
        // When / Then
        assertError(post(CUSTOMER, KEY, "{\"eventId\":"), 400, "TKT0002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "7", "2.5"})
    void shouldRejectNamingQuantityWhenQuantityIsInvalid(String quantity) {
        // When / Then
        assertErrorNaming(post(CUSTOMER, KEY, "{\"eventId\":\"" + EVENT + "\",\"quantity\":" + quantity + "}"),
                "quantity");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectNamingEventIdWhenEventIdIsMissing() {
        // When / Then
        assertErrorNaming(post(CUSTOMER, KEY, "{\"quantity\":2}"), "eventId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc"})
    void shouldRejectNamingCustomerHeaderWhenCustomerIdIsInvalid(String customer) {
        // When / Then
        assertErrorNaming(post(customer, KEY, body(2)), "X-Customer-Id");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectNamingCustomerHeaderWhenCustomerIdIsMissing() {
        // When / Then
        assertErrorNaming(webTestClient.post().uri(ORDERS).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body(2)).exchange(), "X-Customer-Id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc"})
    void shouldRejectWithMissingKeyErrorWhenIdempotencyKeyIsInvalid(String key) {
        // When / Then
        assertError(post(CUSTOMER, key, body(2)), 400, "TKT0004");
        verifyNoInteractions(placeOrderUseCase);
    }

    @Test
    void shouldRejectWithMissingKeyErrorWhenIdempotencyKeyIsMissing() {
        // When / Then
        assertError(webTestClient.post().uri(ORDERS).header("X-Customer-Id", CUSTOMER)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body(2)).exchange(), 400, "TKT0004");
    }

    @Test
    void shouldReturnOwnOrderWithTicketsWhenOrderExists() {
        // Given
        CustomerId customerId = new CustomerId(UUID.fromString(CUSTOMER));
        Order order = Order.place(OrderId.newId(), customerId, new EventId(UUID.fromString(EVENT)), new Quantity(2), 0,
                EXPIRES_AT.minusSeconds(600), Duration.ofMinutes(10));
        Instant now = EXPIRES_AT.minusSeconds(60);
        when(getOrderUseCase.get(new GetOrderQuery(customerId, order.getOrderId())))
                .thenReturn(Mono.just(new OrderView(order, order.effectiveStatus(now), order.ticketsAt(now))));

        // When / Then
        get(order.getOrderId().value().toString())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.meta.code").isEqualTo("TKS0001")
                .jsonPath("$.data.status").isEqualTo("RESERVED")
                .jsonPath("$.data.quantity").isEqualTo(2)
                .jsonPath("$.data.tickets.length()").isEqualTo(2)
                .jsonPath("$.data.tickets[0].status").isEqualTo("RESERVED");
    }

    @Test
    void shouldAnswerNotFoundWhenOrderDoesNotBelongToCustomer() {
        // Given
        when(getOrderUseCase.get(any(GetOrderQuery.class)))
                .thenReturn(Mono.error(BusinessErrorMessage.ORDER_NOT_FOUND.build()));

        // When / Then
        assertError(get(UUID.randomUUID().toString()), 404, "TKB0002");
    }

    @Test
    void shouldRejectNamingOrderIdWhenOrderIdIsNotUuid() {
        // When / Then
        assertErrorNaming(get("not-a-uuid"), "orderId");
        verifyNoInteractions(getOrderUseCase);
    }

    private WebTestClient.ResponseSpec post(String customer, String key, String body) {
        return webTestClient.post().uri(ORDERS).header("X-Customer-Id", customer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec get(String orderId) {
        return webTestClient.get().uri(ORDERS + "/" + orderId).header("X-Customer-Id", CUSTOMER).exchange();
    }

    private static String body(Integer quantity) {
        return "{\"eventId\":\"" + EVENT + "\",\"quantity\":" + quantity + "}";
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
    @Import({SecurityTestConfig.class, AuthenticatedClientConfig.class, OrderRouterRest.class, OrderHandler.class, OrderMapperImpl.class, OrderMapManagement.class,
            BeanValidator.class, GlobalErrorWebExceptionHandler.class, ErrorResponseBuilder.class,
            ErrorStatusMapper.class, JsonMapperConfig.class})
    static class OrderRouteTestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
