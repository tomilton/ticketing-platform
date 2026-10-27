package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.DynamoDbLockRepository;
import co.com.nequi.ticketing.dynamodb.config.SchedulerLockProperties;
import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.config.SweepSettings;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorGateway;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties.Mode;
import co.com.nequi.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-001 and SC-002 of feature 003b: the random mode approves about the configured share of the
 * orders and repeats its decision for the same order, and an order charged against a slow or
 * unavailable gateway is never charged and ends expired with its tickets released by the sweep.
 */
class PaymentSimulatorModesIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final Duration SLOW_LATENCY = Duration.ofSeconds(1);
    private static final Duration SHORT_TTL = Duration.ofSeconds(1);
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(2);
    private static final Double HALF = 0.5;
    private static final Integer DECISIONS = 1_000;
    private static final Integer MINIMUM_APPROVED = 450;
    private static final Integer MAXIMUM_APPROVED = 550;
    private static final Integer CAPACITY = 10;
    private static final Integer QUANTITY = 2;
    private static final SweepSettings SWEEP = new SweepSettings(Duration.ofSeconds(60), 500, 8);

    private static LocalStackRepositories repositories;

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
    }

    @Test
    void shouldApproveAboutHalfAndRepeatEveryDecisionWhenModeIsRandom() {
        // Given
        List<OrderId> orders = Stream.generate(OrderId::newId).limit(DECISIONS).toList();
        PaymentSimulatorGateway gateway = gateway(Mode.RANDOM);
        PaymentSimulatorGateway restarted = gateway(Mode.RANDOM);

        // When
        Mono<List<Boolean>> repeatedApprovals = Flux.fromIterable(orders)
                .flatMap(orderId -> Mono.zip(gateway.charge(orderId), gateway.charge(orderId),
                        restarted.charge(orderId)))
                .filter(decisions -> decisions.getT1() == decisions.getT2() && decisions.getT1() == decisions.getT3())
                .map(decisions -> decisions.getT1() == PaymentResult.APPROVED)
                .collectList();

        // Then
        StepVerifier.create(repeatedApprovals)
                .assertNext(approvals -> {
                    assertThat(approvals).hasSize(DECISIONS);
                    assertThat(approvals).filteredOn(Boolean::booleanValue).hasSizeBetween(MINIMUM_APPROVED,
                            MAXIMUM_APPROVED);
                    assertThat(gateway.countCharges()).isEqualTo(DECISIONS);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"SLOW", "DOWN"})
    void shouldExpireWithoutChargeAndReleaseTicketsWhenGatewayIsSlowOrDown(Mode mode) {
        // Given
        Instant placedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ClockProvider sweepClock = () -> placedAt.plus(SHORT_TTL);
        PaymentSimulatorGateway gateway = gateway(mode);
        Mono<Outcome> outcome = repositories.createEvent(CAPACITY, 1).flatMap(event -> place(event, placedAt)
                .flatMap(order -> processor(gateway, () -> placedAt).process(order.getOrderId())
                        .then(Mono.<TechnicalErrorMessage>empty())
                        .onErrorResume(TechnicalException.class, error -> Mono.just(error.getErrorMessage()))
                        .flatMap(error -> status(order.getOrderId()).map(pending -> new Processed(error, pending)))
                        .flatMap(processed -> sweeper(gateway, sweepClock).expire()
                                .then(Mono.zip(status(order.getOrderId()), inventory(event)))
                                .map(swept -> new Outcome(processed.error(), processed.status(), swept.getT1(),
                                        swept.getT2(), order.getOrderId())))));

        // When / Then
        StepVerifier.create(outcome)
                .assertNext(result -> {
                    assertThat(result.processingError()).isEqualTo(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR);
                    assertThat(result.statusAfterProcessing()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                    assertThat(result.statusAfterSweep()).isEqualTo(OrderStatus.EXPIRED);
                    assertThat(result.inventory().available()).isEqualTo(CAPACITY);
                    assertThat(result.inventory().reserved()).isZero();
                    assertThat(gateway.countCharges()).isZero();
                    assertThat(gateway.isRefunded(result.orderId())).isFalse();
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static PaymentSimulatorGateway gateway(Mode mode) {
        return new PaymentSimulatorGateway(new PaymentSimulatorProperties(mode, Duration.ZERO, TIMEOUT, HALF,
                SLOW_LATENCY));
    }

    private static Mono<Order> place(Event event, Instant at) {
        PlaceOrderUseCase useCase = new PlaceOrderUseCase(repositories.idempotencyRepository(Duration.ofSeconds(10)),
                repositories.eventRepository(), repositories.orderRepository(), message -> Mono.empty(), () -> at,
                new OrderSettings(6, 6, SHORT_TTL, Duration.ofSeconds(5), 4));
        return useCase.place(new PlaceOrderCommand(new CustomerId(UUID.randomUUID()),
                        new IdempotencyKey(UUID.randomUUID()), event.getEventId(), new Quantity(QUANTITY)))
                .flatMap(accepted -> repositories.orderRepository().findById(accepted.orderId()));
    }

    private static ProcessOrderUseCase processor(PaymentSimulatorGateway gateway, ClockProvider clock) {
        return new ProcessOrderUseCase(repositories.orderRepository(), repositories.eventRepository(), gateway, clock);
    }

    private static ExpireReservationsUseCase sweeper(PaymentSimulatorGateway gateway, ClockProvider clock) {
        DynamoDbLockRepository lock = new DynamoDbLockRepository(repositories.client(),
                new SchedulerLockProperties(LocalStackTestSupport.SCHEDULER_LOCKS_TABLE), clock, UUID.randomUUID());
        return new ExpireReservationsUseCase(lock, repositories.orderRepository(), processor(gateway, clock), clock,
                SWEEP);
    }

    private static Mono<OrderStatus> status(OrderId orderId) {
        return repositories.orderRepository().findById(orderId).map(Order::getStatus);
    }

    private static Mono<EventInventory> inventory(Event event) {
        return repositories.inventoryRepository().findShards(event.getEventId()).collectList()
                .map(shards -> EventInventory.aggregate(event.getEventId(), shards));
    }

    private record Processed(TechnicalErrorMessage error, OrderStatus status) {
    }

    private record Outcome(TechnicalErrorMessage processingError, OrderStatus statusAfterProcessing,
                           OrderStatus statusAfterSweep, EventInventory inventory, OrderId orderId) {
    }
}
