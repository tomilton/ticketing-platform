package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.EventStatus;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.shared.CustomerId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDynamoRepositoryIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    private static LocalStackRepositories repositories;
    private static OrderDynamoRepository orderRepository;
    private static IdempotencyDynamoRepository idempotencyRepository;

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
        orderRepository = repositories.orderRepository();
        idempotencyRepository = repositories.idempotencyRepository(Duration.ofSeconds(10));
    }

    @Test
    void shouldWriteOrderAuditShardAndLimitTogetherWhenOrderIsReserved() {
        // Given
        Instant now = Instant.now();
        CustomerId customerId = new CustomerId(UUID.randomUUID());

        // When
        Mono<Order> reserved = repositories.createEvent(10, 1)
                .flatMap(event -> reserve(event, customerId, 2, now));

        // Then
        StepVerifier.create(reserved.flatMap(order -> Mono.zip(Mono.just(order), inventory(order), ticketsHeld(order),
                        auditCount(order))))
                .assertNext(result -> {
                    assertThat(result.getT1().getStatus()).isEqualTo(OrderStatus.RESERVED);
                    assertThat(result.getT2().available()).isEqualTo(8);
                    assertThat(result.getT2().reserved()).isEqualTo(2);
                    assertThat(result.getT3()).isEqualTo(2);
                    assertThat(result.getT4()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectWithoutWritingWhenEventIsCancelledBetweenReadAndReservation() {
        // Given
        Instant now = Instant.now();
        CustomerId customerId = new CustomerId(UUID.randomUUID());

        // When
        Mono<EventInventory> afterRejection = repositories.createEvent(10, 1)
                .flatMap(event -> cancel(event).then(reserve(event, customerId, 2, now))
                        .onErrorResume(BusinessException.class, rejected -> {
                            assertThat(rejected.getBusinessErrorMessage()).isEqualTo(BusinessErrorMessage.EVENT_NOT_ACTIVE);
                            return Mono.empty();
                        })
                        .then(repositories.inventoryRepository().findShards(event.getEventId()).collectList()
                                .map(shards -> EventInventory.aggregate(event.getEventId(), shards))));

        // Then
        StepVerifier.create(afterRejection)
                .assertNext(inventory -> assertThat(inventory.available()).isEqualTo(10))
                .verifyComplete();
    }

    @Test
    void shouldMoveReservedToSoldWhenOrderIsPaid() {
        // Given
        Instant now = Instant.now();

        // When
        Mono<EventInventory> sold = repositories.createEvent(10, 1)
                .flatMap(event -> reserve(event, new CustomerId(UUID.randomUUID()), 3, now))
                .flatMap(order -> orderRepository.apply(order.transitionTo(OrderStatus.PENDING_CONFIRMATION,
                        StatusChangeReason.PAYMENT_REQUESTED, now.plusSeconds(1)), InventoryMovement.NONE))
                .flatMap(pending -> orderRepository.apply(pending.transitionTo(OrderStatus.SOLD,
                        StatusChangeReason.PAYMENT_APPROVED, now.plusSeconds(2)), InventoryMovement.SELL))
                .flatMap(this::inventory);

        // Then
        StepVerifier.create(sold)
                .assertNext(inventory -> {
                    assertThat(inventory.reserved()).isZero();
                    assertThat(inventory.sold()).isEqualTo(3);
                    assertThat(inventory.available()).isEqualTo(7);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnTicketsAndLowerLimitWhenOrderIsReleased() {
        // Given
        Instant now = Instant.now();

        // When
        Mono<Order> cancelled = repositories.createEvent(10, 1)
                .flatMap(event -> reserve(event, new CustomerId(UUID.randomUUID()), 2, now))
                .flatMap(order -> orderRepository.apply(order.transitionTo(OrderStatus.CANCELLED,
                        StatusChangeReason.EVENT_NOT_ACTIVE, now.plusSeconds(1)), InventoryMovement.RELEASE));

        // Then
        StepVerifier.create(cancelled.flatMap(order -> Mono.zip(inventory(order), ticketsHeld(order))))
                .assertNext(result -> {
                    assertThat(result.getT1().available()).isEqualTo(10);
                    assertThat(result.getT2()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void shouldExpireOnlyWhenReservationEndedWhenOrderExpires() {
        // Given
        Instant now = Instant.now();

        // When
        Mono<Order> expired = repositories.createEvent(10, 1)
                .flatMap(event -> reserve(event, new CustomerId(UUID.randomUUID()), 1, now))
                .flatMap(order -> orderRepository.apply(order.transitionTo(OrderStatus.EXPIRED,
                                StatusChangeReason.RESERVATION_EXPIRED, now.plusSeconds(1)), InventoryMovement.RELEASE)
                        .onErrorResume(BusinessException.class, notYet -> orderRepository.apply(order.transitionTo(
                                OrderStatus.EXPIRED, StatusChangeReason.RESERVATION_EXPIRED, order.getExpiresAt()),
                                InventoryMovement.RELEASE)));

        // Then
        StepVerifier.create(expired.flatMap(order -> orderRepository.findById(order.getOrderId())))
                .assertNext(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED))
                .verifyComplete();
    }

    private Mono<Order> reserve(Event event, CustomerId customerId, Integer quantity, Instant now) {
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        Order order = Order.place(OrderId.newId(), customerId, event.getEventId(), new Quantity(quantity), 0, now, TTL);
        return idempotencyRepository.claim(customerId, key, RequestFingerprint.of(event.getEventId(), new Quantity(order.getQuantity())), now)
                .cast(IdempotencyClaim.Granted.class)
                .flatMap(granted -> orderRepository.reserve(order, event,
                        new ReservationContext(customerId, key, granted.lockToken(), 6), now));
    }

    private Mono<Void> cancel(Event event) {
        return Mono.fromFuture(() -> repositories.client().updateItem(UpdateItemRequest.builder()
                        .tableName(LocalStackTestSupport.EVENTS_TABLE)
                        .key(Map.of("eventId", AttributeValue.fromS(event.getEventId().value().toString())))
                        .updateExpression("SET #status = :cancelled")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(":cancelled", AttributeValue.fromS(EventStatus.CANCELLED.name())))
                        .build()))
                .then();
    }

    private Mono<EventInventory> inventory(Order order) {
        return repositories.inventoryRepository().findShards(order.getEventId()).collectList()
                .map(shards -> EventInventory.aggregate(order.getEventId(), shards));
    }

    private Mono<Integer> ticketsHeld(Order order) {
        return Mono.fromFuture(() -> repositories.client().getItem(GetItemRequest.builder()
                        .tableName(LocalStackTestSupport.CUSTOMER_LIMITS_TABLE)
                        .key(Map.of("limitId", AttributeValue.fromS(order.getCustomerId().value() + "#"
                                + order.getEventId().value())))
                        .consistentRead(Boolean.TRUE)
                        .build()))
                .map(response -> Integer.valueOf(response.item().get("ticketsHeld").n()));
    }

    private Mono<Integer> auditCount(Order order) {
        return Mono.fromFuture(() -> repositories.client().query(QueryRequest.builder()
                        .tableName(LocalStackTestSupport.ORDER_AUDIT_TABLE)
                        .keyConditionExpression("orderId = :orderId")
                        .expressionAttributeValues(Map.of(":orderId",
                                AttributeValue.fromS(order.getOrderId().value().toString())))
                        .build()))
                .map(response -> response.count());
    }
}
