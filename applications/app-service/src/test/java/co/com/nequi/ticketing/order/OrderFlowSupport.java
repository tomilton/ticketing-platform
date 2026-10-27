package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.MainApplication;
import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Starts the application in the {@code api} and {@code worker} roles against the shared LocalStack
 * container and talks to them as a client would.
 */
final class OrderFlowSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private OrderFlowSupport() {
    }

    static ConfigurableApplicationContext start(String role, Map<String, String> overrides) {
        Map<String, String> properties = new HashMap<>(LocalStackTestSupport.springProperties());
        properties.put("app.role", role);
        properties.put("server.port", "0");
        properties.put("orders.republish-interval", "1s");
        properties.put("orders.republish-grace", "1s");
        properties.put("adapters.sqs.listener.wait-time", "1s");
        properties.putAll(overrides);
        List<String> arguments = new ArrayList<>();
        properties.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        return new SpringApplicationBuilder(MainApplication.class).run(arguments.toArray(String[]::new));
    }

    static WebClient client(ConfigurableApplicationContext api) {
        return WebClient.builder()
                .baseUrl("http://localhost:" + api.getEnvironment().getProperty("local.server.port") + "/api/v1")
                .build();
    }

    static Mono<String> createEvent(WebClient client, Integer capacity) {
        return client.post().uri("/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Flow\",\"venue\":{\"name\":\"Movistar Arena\",\"city\":\"Bogota\"},"
                        + "\"date\":\"2030-03-20T01:00:00Z\",\"capacity\":" + capacity + "}")
                .retrieve().bodyToMono(String.class)
                .map(body -> JSON.readTree(body).path("data").path("eventId").asString());
    }

    static Mono<String> placeOrder(WebClient client, String customer, String key, String eventId, Integer quantity) {
        return client.post().uri("/orders").header("X-Customer-Id", customer).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"eventId\":\"" + eventId + "\",\"quantity\":" + quantity + "}")
                .exchangeToMono(response -> response.bodyToMono(String.class));
    }

    static Mono<JsonNode> order(WebClient client, String customer, String orderId) {
        return client.get().uri("/orders/" + orderId).header("X-Customer-Id", customer)
                .retrieve().bodyToMono(String.class)
                .map(body -> JSON.readTree(body).path("data"));
    }

    static Mono<JsonNode> awaitStatus(WebClient client, String customer, String orderId, String status,
                                      Duration timeout) {
        return Flux.interval(POLL_INTERVAL).onBackpressureDrop().onBackpressureDrop()
                .concatMap(tick -> order(client, customer, orderId))
                .filter(order -> status.equals(order.path("status").asString()))
                .next()
                .timeout(timeout);
    }

    static Mono<JsonNode> availability(WebClient client, String eventId) {
        return client.get().uri("/events/" + eventId + "/availability").retrieve().bodyToMono(String.class)
                .map(body -> JSON.readTree(body).path("data"));
    }

    static Mono<Integer> auditRecords(String orderId) {
        return Mono.fromFuture(() -> LocalStackTestSupport.asyncClient().query(QueryRequest.builder()
                        .tableName(LocalStackTestSupport.ORDER_AUDIT_TABLE)
                        .keyConditionExpression("orderId = :orderId")
                        .expressionAttributeValues(Map.of(":orderId", AttributeValue.fromS(orderId)))
                        .build()))
                .map(response -> response.count());
    }

    static Mono<String> queueUrl(SqsAsyncClient sqs, String queue) {
        return Mono.fromFuture(() -> sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queue).build()))
                .map(GetQueueUrlResponse::queueUrl);
    }

    static Mono<Message> awaitDeadLetter(SqsAsyncClient sqs, String bodyFragment, Duration timeout) {
        return queueUrl(sqs, LocalStackTestSupport.ORDER_PLACED_DLQ)
                .flatMap(url -> Flux.interval(POLL_INTERVAL).onBackpressureDrop().onBackpressureDrop()
                        .concatMap(tick -> Mono.fromFuture(() -> sqs.receiveMessage(ReceiveMessageRequest.builder()
                                        .queueUrl(url).maxNumberOfMessages(10).messageAttributeNames("All")
                                        .visibilityTimeout(0).build()))
                                .flatMapIterable(ReceiveMessageResponse::messages))
                        .filter(message -> message.body().contains(bodyFragment))
                        .next()
                        .timeout(timeout));
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
