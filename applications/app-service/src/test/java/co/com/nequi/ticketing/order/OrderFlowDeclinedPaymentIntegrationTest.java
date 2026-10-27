package co.com.nequi.ticketing.order;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static co.com.nequi.ticketing.order.OrderFlowSupport.JSON;
import static co.com.nequi.ticketing.order.OrderFlowSupport.newId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3-2 end to end: a declined payment cancels the order and gives its tickets back.
 */
class OrderFlowDeclinedPaymentIntegrationTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private static ConfigurableApplicationContext api;
    private static ConfigurableApplicationContext worker;
    private static WebClient client;

    @BeforeAll
    static void startContexts() {
        api = OrderFlowSupport.start("api", Map.of());
        worker = OrderFlowSupport.start("worker", Map.of("adapters.payment.mode", "DECLINE"));
        client = OrderFlowSupport.client(api);
    }

    @AfterAll
    static void stopContexts() {
        worker.close();
        api.close();
    }

    @Test
    void shouldCancelOrderAndReturnTicketsWhenPaymentIsDeclined() {
        // Given
        String customer = newId();

        // When
        Mono<String> result = OrderFlowSupport.createEvent(client, 10)
                .flatMap(eventId -> OrderFlowSupport.placeOrder(client, customer, newId(), eventId, 2)
                        .map(body -> JSON.readTree(body).path("data").path("orderId").asString())
                        .flatMap(orderId -> OrderFlowSupport.awaitStatus(client, customer, orderId, "CANCELLED",
                                Duration.ofSeconds(10)))
                        .flatMap(order -> OrderFlowSupport.availability(client, eventId)
                                .map(availability -> order.path("tickets").get(0).path("status").asString() + " "
                                        + availability.path("available").asInt())));

        // Then
        StepVerifier.create(result)
                .assertNext(found -> assertThat(found).isEqualTo("AVAILABLE 10"))
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }
}
