package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.util.Map;

import static co.com.nequi.ticketing.order.OrderFlowSupport.JSON;
import static co.com.nequi.ticketing.order.OrderFlowSupport.newId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-020 end to end: a gateway slower than its timeout is a retryable error, so SQS delivers the
 * message again and moves it to the dead letter queue after the last attempt; the order stays
 * pending and is never charged twice.
 */
class OrderFlowSlowGatewayIntegrationTest {

    private static final Duration DEAD_LETTER_TIMEOUT = Duration.ofSeconds(90);

    private static ConfigurableApplicationContext api;
    private static ConfigurableApplicationContext worker;
    private static WebClient client;

    @BeforeAll
    static void startContexts() {
        api = OrderFlowSupport.start("api", Map.of());
        worker = OrderFlowSupport.start("worker", Map.of("adapters.payment.latency", "3s",
                "adapters.payment.timeout", "1s"));
        client = OrderFlowSupport.client(api);
    }

    @AfterAll
    static void stopContexts() {
        worker.close();
        api.close();
    }

    @Test
    void shouldReachDeadLetterQueueAfterLastDeliveryWhenGatewayIsTooSlow() {
        // Given
        String customer = newId();

        // When
        Mono<Message> deadLettered = OrderFlowSupport.createEvent(client, 10)
                .flatMap(eventId -> OrderFlowSupport.placeOrder(client, customer, newId(), eventId, 1))
                .map(body -> JSON.readTree(body).path("data").path("orderId").asString())
                .flatMap(orderId -> OrderFlowSupport.awaitDeadLetter(LocalStackTestSupport.sqsAsyncClient(), orderId,
                        DEAD_LETTER_TIMEOUT));

        // Then
        StepVerifier.create(deadLettered)
                .assertNext(message -> assertThat(message.messageAttributes()).doesNotContainKey("failureCode"))
                .expectComplete()
                .verify(DEAD_LETTER_TIMEOUT.plusSeconds(10));
    }
}
