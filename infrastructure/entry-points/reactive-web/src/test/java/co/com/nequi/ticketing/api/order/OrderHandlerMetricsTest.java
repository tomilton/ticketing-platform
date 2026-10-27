package co.com.nequi.ticketing.api.order;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.usecase.getorder.GetOrderUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest
@ContextConfiguration(classes = OrderRouteIntegrationTest.OrderRouteTestConfig.class)
class OrderHandlerMetricsTest {

    private static final String BODY = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    private GetOrderUseCase getOrderUseCase;

    @Test
    void shouldCountAcceptedAndRejectedPurchasesByResultWhenOrdersArePlaced() {
        // Given
        when(placeOrderUseCase.place(any(PlaceOrderCommand.class)))
                .thenReturn(Mono.just(new PlacementOutcome.Accepted(OrderId.newId(), OrderStatus.RESERVED, Instant.now())))
                .thenReturn(Mono.error(BusinessErrorMessage.TICKETS_SOLD_OUT.build()));

        double acceptedBefore = count("accepted");
        double soldOutBefore = count("TKB0003");

        // When
        post().expectStatus().isAccepted();
        post().expectStatus().isEqualTo(409);

        // Then
        assertThat(count("accepted")).isEqualTo(acceptedBefore + 1);
        assertThat(count("TKB0003")).isEqualTo(soldOutBefore + 1);
        assertThat(meterRegistry.get(OrderHandler.OUTCOMES_METRIC).counter().getId().getTags())
                .noneMatch(tag -> tag.getKey().equals("orderId") || tag.getKey().equals("customerId"));
    }

    private double count(String result) {
        return meterRegistry.counter(OrderHandler.OUTCOMES_METRIC, "result", result).count();
    }

    private WebTestClient.ResponseSpec post() {
        return webTestClient.post().uri("/api/v1/orders")
                .header("X-Customer-Id", UUID.randomUUID().toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY).exchange();
    }
}
