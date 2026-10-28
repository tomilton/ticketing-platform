package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorGateway;
import co.com.nequi.ticketing.security.TestIdentityProvider;
import co.com.nequi.ticketing.sqs.commons.OrderPlacedMessageCodec;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static co.com.nequi.ticketing.order.OrderFlowSupport.JSON;
import static co.com.nequi.ticketing.order.OrderFlowSupport.newId;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end flow with an {@code api} and a {@code worker} context against the same LocalStack:
 * SC-003, SC-004, SC-006, US3-6, US3-7, FR-018 and FR-027 of the orders, and the access to an order
 * (only its owner or an administrator).
 */
class OrderFlowIntegrationTest {

    private static final Duration SOLD_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private static ConfigurableApplicationContext api;
    private static ConfigurableApplicationContext worker;
    private static WebClient client;
    private static SqsAsyncClient sqs;

    @BeforeAll
    static void startContexts() {
        api = OrderFlowSupport.start("api", Map.of());
        worker = OrderFlowSupport.start("worker", Map.of("adapters.payment.mode", "APPROVE"));
        client = OrderFlowSupport.client(api);
        sqs = LocalStackTestSupport.sqsAsyncClient();
    }

    @AfterAll
    static void stopContexts() {
        worker.close();
        api.close();
    }

    @Test
    void shouldSellApprovedOrderInLessThanFiveSecondsAndShowItInAvailability() {
        // Given
        String customer = newId();

        // When
        Mono<String> soldEventAvailability = OrderFlowSupport.createEvent(client, 10)
                .flatMap(eventId -> OrderFlowSupport.placeOrder(client, customer, newId(), eventId, 2)
                        .map(body -> JSON.readTree(body).path("data").path("orderId").asString())
                        .flatMap(orderId -> OrderFlowSupport.awaitStatus(client, customer, orderId, "SOLD", SOLD_TIMEOUT))
                        .then(OrderFlowSupport.availability(client, eventId))
                        .map(availability -> availability.path("available").asInt() + "/"
                                + availability.path("sold").asInt()));

        // Then
        StepVerifier.create(soldEventAvailability)
                .expectNext("8/2")
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldHideOrderFromAnotherCustomerAndShowItToAdministratorWhenOrderIsRead() {
        // Given
        String owner = newId();

        // When
        Mono<String> statuses = OrderFlowSupport.createEvent(client, 10)
                .flatMap(eventId -> OrderFlowSupport.placeOrder(client, owner, newId(), eventId, 1))
                .map(body -> JSON.readTree(body).path("data").path("orderId").asString())
                .flatMap(orderId -> readStatus(orderId, TestIdentityProvider.customer(newId()))
                        .zipWith(readStatus(orderId, TestIdentityProvider.admin()),
                                (otherCustomer, administrator) -> otherCustomer + "/" + administrator));

        // Then
        StepVerifier.create(statuses)
                .expectNext("404/200")
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldReturnTenIdenticalBodiesWhenSamePurchaseIsRepeatedTenTimes() {
        // Given
        String customer = newId();
        String key = newId();

        // When
        Mono<Long> distinctBodies = OrderFlowSupport.createEvent(client, 10)
                .flatMapMany(eventId -> Flux.range(0, 10)
                        .concatMap(index -> OrderFlowSupport.placeOrder(client, customer, key, eventId, 2)))
                .distinct()
                .count();

        // Then
        StepVerifier.create(distinctBodies)
                .expectNext(1L)
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldChargeOnceAndAuditEachStatusOnceWhenSameMessageArrivesTwice() {
        // Given
        String customer = newId();
        OrderPlacedMessageCodec codec = api.getBean(OrderPlacedMessageCodec.class);
        PaymentSimulatorGateway payments = worker.getBean(PaymentSimulatorGateway.class);

        // When
        Mono<String> sold = OrderFlowSupport.createEvent(client, 10)
                .flatMap(eventId -> OrderFlowSupport.placeOrder(client, customer, newId(), eventId, 1)
                        .map(body -> JSON.readTree(body).path("data").path("orderId").asString())
                        .flatMap(orderId -> OrderFlowSupport.awaitStatus(client, customer, orderId, "SOLD", SOLD_TIMEOUT)
                                .thenReturn(orderId)
                                .flatMap(id -> duplicate(codec, id, eventId).then(duplicate(codec, id, eventId))
                                        .then(Flux.merge(duplicate(codec, id, eventId), duplicate(codec, id, eventId))
                                                .then())
                                        .then(Mono.delay(Duration.ofSeconds(3)))
                                        .thenReturn(id))));

        // Then
        StepVerifier.create(sold.flatMap(orderId -> OrderFlowSupport.auditRecords(orderId)
                        .map(records -> new Object[]{orderId, records})))
                .assertNext(result -> {
                    assertThat(result[1]).isEqualTo(3);
                    assertThat(payments.isCharged(new OrderId(UUID.fromString((String) result[0])))).isTrue();
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldProcessOrderThroughRepublicationWhenPublicationFailed() {
        // Given
        String customer = newId();

        // When
        Mono<String> sold = OrderFlowSupport.createEvent(client, 10)
                .flatMap(eventId -> placeWithoutPublishing(customer, eventId))
                .flatMap(orderId -> OrderFlowSupport.awaitStatus(client, customer, orderId, "SOLD",
                        Duration.ofSeconds(15)))
                .map(order -> order.path("status").asString());

        // Then
        StepVerifier.create(sold)
                .expectNext("SOLD")
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldCancelAndReturnTicketsWhenEventIsCancelledBeforeProcessing() {
        // Given
        String customer = newId();

        // When
        Mono<String> cancelled = OrderFlowSupport.createEvent(client, 10)
                .flatMap(eventId -> placeWithoutPublishing(customer, eventId)
                        .flatMap(orderId -> cancelEvent(eventId)
                                .then(OrderFlowSupport.awaitStatus(client, customer, orderId, "CANCELLED",
                                        Duration.ofSeconds(15))))
                        .then(OrderFlowSupport.availability(client, eventId)))
                .map(availability -> availability.path("available").asInt() + "/"
                        + availability.path("reserved").asInt());

        // Then
        StepVerifier.create(cancelled)
                .expectNext("10/0")
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldMoveMalformedMessageToDeadLetterQueueWithInvalidMessageCode() {
        // Given
        String marker = newId();

        // When
        Mono<String> failureCode = OrderFlowSupport.queueUrl(sqs, LocalStackTestSupport.ORDER_PLACED_QUEUE)
                .flatMap(url -> Mono.fromFuture(() -> sqs.sendMessage(SendMessageRequest.builder().queueUrl(url)
                        .messageBody("{\"type\":\"Other\",\"marker\":\"" + marker + "\"}").build())))
                .then(OrderFlowSupport.awaitDeadLetter(sqs, marker, Duration.ofSeconds(15)))
                .map(message -> message.messageAttributes().get("failureCode").stringValue());

        // Then
        StepVerifier.create(failureCode)
                .expectNext("TKT0008")
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static Mono<Integer> readStatus(String orderId, String token) {
        return client.get().uri("/orders/" + orderId)
                .header(HttpHeaders.AUTHORIZATION, TestIdentityProvider.bearer(token))
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()));
    }

    private static Mono<Void> duplicate(OrderPlacedMessageCodec codec, String orderId, String eventId) {
        String body = codec.encode(new OrderPlacedMessage(UUID.randomUUID(), new OrderId(UUID.fromString(orderId)),
                new EventId(UUID.fromString(eventId)), new Quantity(1), Instant.now()));
        return OrderFlowSupport.queueUrl(sqs, LocalStackTestSupport.ORDER_PLACED_QUEUE)
                .flatMap(url -> Mono.fromFuture(() -> sqs.sendMessage(SendMessageRequest.builder().queueUrl(url)
                        .messageBody(body).build())))
                .then();
    }

    private static Mono<String> placeWithoutPublishing(String customer, String eventId) {
        PlaceOrderUseCase useCase = new PlaceOrderUseCase(api.getBean(IdempotencyRepository.class),
                api.getBean(EventRepository.class), api.getBean(OrderRepository.class),
                message -> Mono.error(new IllegalStateException("queue down")), api.getBean(ClockProvider.class),
                api.getBean(OrderSettings.class));
        return useCase.place(new PlaceOrderCommand(new CustomerId(UUID.fromString(customer)),
                        new IdempotencyKey(UUID.randomUUID()), new EventId(UUID.fromString(eventId)), new Quantity(1)))
                .map(accepted -> accepted.orderId().value().toString());
    }

    private static Mono<Void> cancelEvent(String eventId) {
        return Mono.fromFuture(() -> LocalStackTestSupport.asyncClient().updateItem(UpdateItemRequest.builder()
                        .tableName(LocalStackTestSupport.EVENTS_TABLE)
                        .key(Map.of("eventId", AttributeValue.fromS(eventId)))
                        .updateExpression("SET #status = :cancelled")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(":cancelled", AttributeValue.fromS("CANCELLED")))
                        .build()))
                .then();
    }
}
