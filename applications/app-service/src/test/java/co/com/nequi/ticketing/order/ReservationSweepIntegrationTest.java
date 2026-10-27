package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.DynamoDbLockRepository;
import co.com.nequi.ticketing.dynamodb.config.SchedulerLockProperties;
import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.config.SweepSettings;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-001 to SC-003 of feature 003: the sweep releases the expired reservations exactly once, even
 * with two replicas or a concurrent processing of the same order.
 */
class ReservationSweepIntegrationTest {

    private static final Duration SHORT_TTL = Duration.ofSeconds(1);
    private static final Duration LONG_TTL = Duration.ofHours(1);
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(2);
    private static final Integer MASS_ORDERS = 200;
    private static final SweepSettings SWEEP = new SweepSettings(Duration.ofSeconds(60), 500, 8);

    private static LocalStackRepositories repositories;
    private static final Set<OrderId> REFUNDED = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
    }

    @Test
    void shouldExpireReservedAndPendingOrdersAndKeepValidOneWhenSweepRuns() {
        // Given
        Instant placedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant sweepAt = placedAt.plus(SHORT_TTL);
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        Mono<Scenario> scenario = repositories.createEvent(10, 1).flatMap(event -> Mono.zip(
                        place(event, customerId, 2, SHORT_TTL, placedAt),
                        place(event, customerId, 1, SHORT_TTL, placedAt)
                                .flatMap(order -> repositories.orderRepository().apply(order.transitionTo(
                                        OrderStatus.PENDING_CONFIRMATION, StatusChangeReason.PAYMENT_REQUESTED, placedAt),
                                        InventoryMovement.NONE)),
                        place(event, new CustomerId(UUID.randomUUID()), 3, LONG_TTL, placedAt))
                .flatMap(orders -> sweeper(UUID.randomUUID(), () -> sweepAt).expire()
                        .flatMap(released -> Mono.zip(status(orders.getT1()), status(orders.getT2()),
                                        status(orders.getT3()), inventory(event), ticketsHeld(customerId, event),
                                        auditOf(orders.getT1().getOrderId()))
                                .map(read -> new Scenario(released, orders.getT2().getOrderId(), read.getT1(),
                                        read.getT2(), read.getT3(), read.getT4(), read.getT5(), read.getT6())))));

        // When / Then
        StepVerifier.create(scenario)
                .assertNext(result -> {
                    assertThat(result.released()).isGreaterThanOrEqualTo(2L);
                    assertThat(result.reserved()).isEqualTo(OrderStatus.EXPIRED);
                    assertThat(result.pending()).isEqualTo(OrderStatus.EXPIRED);
                    assertThat(result.valid()).isEqualTo(OrderStatus.RESERVED);
                    assertThat(result.inventory().available()).isEqualTo(7);
                    assertThat(result.inventory().reserved()).isEqualTo(3);
                    assertThat(result.ticketsHeld()).isZero();
                    assertThat(REFUNDED).contains(result.pendingId());
                    assertThat(result.audit()).contains("EXPIRED|RESERVATION_EXPIRED|WORKER");
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldReleaseEveryOrderOnceWhenTwoReplicasSweepAtTheSameTime() {
        // Given
        Instant placedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ClockProvider sweepClock = () -> placedAt.plus(SHORT_TTL);
        Mono<MassOutcome> outcome = repositories.createEvent(MASS_ORDERS, 4).flatMap(event -> Flux.range(0, MASS_ORDERS)
                .flatMap(index -> place(event, new CustomerId(UUID.randomUUID()), 1, SHORT_TTL, placedAt), 32)
                .collectList()
                .flatMap(orders -> Mono.zip(sweeper(UUID.randomUUID(), sweepClock).expire(),
                                sweeper(UUID.randomUUID(), sweepClock).expire())
                        .flatMap(results -> Flux.fromIterable(orders).flatMap(ReservationSweepIntegrationTest::status)
                                .collectList()
                                .zipWith(inventory(event))
                                .map(read -> new MassOutcome(List.of(results.getT1(), results.getT2()), read.getT1(),
                                        read.getT2())))));

        // When / Then
        StepVerifier.create(outcome)
                .assertNext(result -> {
                    assertThat(result.released()).contains(0L);
                    assertThat(result.released().stream().mapToLong(Long::longValue).sum())
                            .isGreaterThanOrEqualTo(MASS_ORDERS.longValue());
                    assertThat(result.statuses()).hasSize(MASS_ORDERS).containsOnly(OrderStatus.EXPIRED);
                    assertThat(result.inventory().available()).isEqualTo(MASS_ORDERS);
                    assertThat(result.inventory().reserved()).isZero();
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldMoveInventoryOnceWhenSweepAndProcessingExpireSameOrder() {
        // Given
        Instant placedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ClockProvider sweepClock = () -> placedAt.plus(SHORT_TTL);
        Mono<RaceOutcome> outcome = repositories.createEvent(10, 1).flatMap(event ->
                place(event, new CustomerId(UUID.randomUUID()), 2, SHORT_TTL, placedAt)
                        .flatMap(order -> Mono.zip(sweeper(UUID.randomUUID(), sweepClock).expire(),
                                        processor(sweepClock).process(order.getOrderId()))
                                .then(Mono.zip(inventory(event), auditOf(order.getOrderId())))
                                .map(read -> new RaceOutcome(read.getT1(), read.getT2()))));

        // When / Then
        StepVerifier.create(outcome)
                .assertNext(result -> {
                    assertThat(result.inventory().available()).isEqualTo(10);
                    assertThat(result.inventory().reserved()).isZero();
                    assertThat(result.audit()).filteredOn(record -> record.startsWith("EXPIRED|")).hasSize(1);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static Mono<Order> place(Event event, CustomerId customerId, Integer quantity, Duration ttl, Instant at) {
        PlaceOrderUseCase useCase = new PlaceOrderUseCase(repositories.idempotencyRepository(Duration.ofSeconds(10)),
                repositories.eventRepository(), repositories.orderRepository(), message -> Mono.empty(), () -> at,
                new OrderSettings(6, 6, ttl, Duration.ofSeconds(5), 4));
        return useCase.place(new PlaceOrderCommand(customerId, new IdempotencyKey(UUID.randomUUID()),
                        event.getEventId(), new Quantity(quantity)))
                .flatMap(accepted -> repositories.orderRepository().findById(accepted.orderId()));
    }

    private static ExpireReservationsUseCase sweeper(UUID instanceId, ClockProvider clock) {
        DynamoDbLockRepository lock = new DynamoDbLockRepository(repositories.client(),
                new SchedulerLockProperties(LocalStackTestSupport.SCHEDULER_LOCKS_TABLE), clock, instanceId);
        return new ExpireReservationsUseCase(lock, repositories.orderRepository(), processor(clock), clock, SWEEP);
    }

    private static ProcessOrderUseCase processor(ClockProvider clock) {
        return new ProcessOrderUseCase(repositories.orderRepository(), repositories.eventRepository(),
                new RecordingPaymentGateway(), clock);
    }

    private static Mono<OrderStatus> status(Order order) {
        return repositories.orderRepository().findById(order.getOrderId()).map(Order::getStatus);
    }

    private static Mono<EventInventory> inventory(Event event) {
        return repositories.inventoryRepository().findShards(event.getEventId()).collectList()
                .map(shards -> EventInventory.aggregate(event.getEventId(), shards));
    }

    private static Mono<Integer> ticketsHeld(CustomerId customerId, Event event) {
        return Mono.fromFuture(() -> repositories.client().getItem(GetItemRequest.builder()
                        .tableName(LocalStackTestSupport.CUSTOMER_LIMITS_TABLE)
                        .key(Map.of("limitId", AttributeValue.fromS(customerId.value() + "#" + event.getEventId().value())))
                        .consistentRead(Boolean.TRUE)
                        .build()))
                .map(response -> Integer.valueOf(response.item().get("ticketsHeld").n()));
    }

    private static Mono<List<String>> auditOf(OrderId orderId) {
        return Mono.fromFuture(() -> repositories.client().query(QueryRequest.builder()
                        .tableName(LocalStackTestSupport.ORDER_AUDIT_TABLE)
                        .keyConditionExpression("orderId = :orderId")
                        .expressionAttributeValues(Map.of(":orderId", AttributeValue.fromS(orderId.value().toString())))
                        .consistentRead(Boolean.TRUE)
                        .build()))
                .map(response -> response.items().stream()
                        .map(item -> item.get("toStatus").s() + "|" + item.get("reason").s() + "|"
                                + item.get("actor").s())
                        .toList());
    }

    private static final class RecordingPaymentGateway implements PaymentGateway {

        @Override
        public Mono<PaymentResult> charge(OrderId orderId) {
            return Mono.just(PaymentResult.APPROVED);
        }

        @Override
        public Mono<Void> refund(OrderId orderId) {
            return Mono.fromRunnable(() -> REFUNDED.add(orderId));
        }
    }

    private record Scenario(Long released, OrderId pendingId, OrderStatus reserved, OrderStatus pending,
                            OrderStatus valid, EventInventory inventory, Integer ticketsHeld, List<String> audit) {
    }

    private record MassOutcome(List<Long> released, List<OrderStatus> statuses, EventInventory inventory) {
    }

    private record RaceOutcome(EventInventory inventory, List<String> audit) {
    }
}
