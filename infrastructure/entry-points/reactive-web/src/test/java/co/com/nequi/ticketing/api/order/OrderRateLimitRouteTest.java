package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.api.support.TestTokens;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = OrderRouteIntegrationTest.OrderRouteTestConfig.class)
@TestPropertySource(properties = {"rate-limit.orders.limit-for-period=2", "rate-limit.orders.period=1h"})
class OrderRateLimitRouteTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String BODY = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    private GetOrderUseCase getOrderUseCase;

    @Test
    void shouldRejectWithRetryAfterWithoutPlacingWhenCustomerExceedsLimit() {
        // Given
        String token = TestTokens.customer(UUID.randomUUID());
        when(placeOrderUseCase.place(any(PlaceOrderCommand.class))).thenReturn(Mono.just(
                new PlacementOutcome.Accepted(OrderId.newId(), OrderStatus.RESERVED, Instant.now())));
        post(token).expectStatus().isAccepted();
        post(token).expectStatus().isAccepted();

        // When / Then
        post(token).expectStatus().isEqualTo(429)
                .expectHeader().value(HttpHeaders.RETRY_AFTER,
                        seconds -> assertThat(Long.valueOf(seconds)).isBetween(1L, 3600L))
                .expectBody()
                .jsonPath("$.errors[0].code").isEqualTo("TKT0012");
        verify(placeOrderUseCase, times(2)).place(any(PlaceOrderCommand.class));
    }

    @Test
    void shouldAcceptPurchaseOfAnotherCustomerWhenOneCustomerExceedsLimit() {
        // Given
        String limited = TestTokens.customer(UUID.randomUUID());
        when(placeOrderUseCase.place(any(PlaceOrderCommand.class))).thenReturn(Mono.just(
                new PlacementOutcome.Accepted(OrderId.newId(), OrderStatus.RESERVED, Instant.now())));
        post(limited).expectStatus().isAccepted();
        post(limited).expectStatus().isAccepted();
        post(limited).expectStatus().isEqualTo(429);

        // When / Then
        post(TestTokens.customer(UUID.randomUUID())).expectStatus().isAccepted();
    }

    private WebTestClient.ResponseSpec post(String token) {
        return webTestClient.post().uri(ORDERS)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY).exchange();
    }
}
