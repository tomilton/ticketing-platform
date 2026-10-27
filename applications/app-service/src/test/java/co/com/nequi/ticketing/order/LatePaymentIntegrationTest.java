package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.OrderDynamoRepository;
import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorGateway;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-005: a payment approved when the reservation already ended never sells the tickets; the order
 * expires, the inventory is released and the charge is refunded, also when another copy expired the
 * order while this one was charging.
 */
class LatePaymentIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private static LocalStackRepositories repositories;
    private static OrderDynamoRepository orderRepository;

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
        orderRepository = repositories.orderRepository();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L})
    void shouldExpireReleaseAndRefundWhenPaymentIsApprovedAtOrAfterExpiration(Long millisAfterExpiration) {
        // Given
        Instant now = Instant.now();
        PaymentSimulatorGateway payments = approvingGateway();

        // When
        Mono<Result> result = repositories.createEvent(10, 1)
                .flatMap(event -> pending(event, now))
                .flatMap(order -> {
                    AtomicInteger reads = new AtomicInteger();
                    ClockProvider clock = () -> reads.getAndIncrement() == 0 ? now.plusSeconds(1)
                            : order.getExpiresAt().plusMillis(millisAfterExpiration);
                    return new ProcessOrderUseCase(orderRepository, repositories.eventRepository(), payments, clock)
                            .process(order.getOrderId())
                            .flatMap(outcome -> inventory(order).map(inventory -> new Result(outcome, inventory,
                                    payments.isRefunded(order.getOrderId()))));
                });

        // Then
        StepVerifier.create(result)
                .assertNext(found -> {
                    assertThat(found.outcome()).isEqualTo(ProcessingOutcome.EXPIRED);
                    assertThat(found.inventory().available()).isEqualTo(10);
                    assertThat(found.refunded()).isTrue();
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldRefundWhenAnotherCopyExpiredTheOrderWhileThisOneWasCharging() {
        // Given
        Instant now = Instant.now();
        PaymentSimulatorGateway payments = approvingGateway();

        // When
        Mono<Result> result = repositories.createEvent(10, 1)
                .flatMap(event -> pending(event, now))
                .flatMap(order -> {
                    ProcessOrderUseCase otherCopy = new ProcessOrderUseCase(orderRepository,
                            repositories.eventRepository(), payments, order::getExpiresAt);
                    PaymentGateway chargingAfterTheOtherCopy = new PaymentGateway() {
                        @Override
                        public Mono<PaymentResult> charge(OrderId orderId) {
                            return otherCopy.process(orderId).then(payments.charge(orderId));
                        }

                        @Override
                        public Mono<Void> refund(OrderId orderId) {
                            return payments.refund(orderId);
                        }
                    };
                    return new ProcessOrderUseCase(orderRepository, repositories.eventRepository(),
                            chargingAfterTheOtherCopy, () -> now.plusSeconds(1))
                            .process(order.getOrderId())
                            .flatMap(outcome -> inventory(order).map(inventory -> new Result(outcome, inventory,
                                    payments.isRefunded(order.getOrderId()))));
                });

        // Then
        StepVerifier.create(result)
                .assertNext(found -> {
                    assertThat(found.inventory().available()).isEqualTo(10);
                    assertThat(found.refunded()).isTrue();
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static Mono<Order> pending(Event event, Instant now) {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        Order order = Order.place(OrderId.newId(), customerId, event.getEventId(), new Quantity(2), 0, now, TTL);
        return repositories.idempotencyRepository(Duration.ofSeconds(10))
                .claim(customerId, key, RequestFingerprint.of(event.getEventId(), new Quantity(order.getQuantity())), now)
                .cast(IdempotencyClaim.Granted.class)
                .flatMap(granted -> orderRepository.reserve(order, event,
                        new ReservationContext(customerId, key, granted.lockToken(), 6), now))
                .flatMap(reserved -> orderRepository.apply(reserved.transitionTo(OrderStatus.PENDING_CONFIRMATION,
                        StatusChangeReason.PAYMENT_REQUESTED, now), InventoryMovement.NONE));
    }

    private static Mono<EventInventory> inventory(Order order) {
        return repositories.inventoryRepository().findShards(order.getEventId()).collectList()
                .map(shards -> EventInventory.aggregate(order.getEventId(), shards));
    }

    private static PaymentSimulatorGateway approvingGateway() {
        return new PaymentSimulatorGateway(new PaymentSimulatorProperties(PaymentSimulatorProperties.Mode.APPROVE,
                Duration.ZERO, Duration.ofSeconds(2), 0.5, Duration.ofSeconds(5)));
    }

    private record Result(ProcessingOutcome outcome, EventInventory inventory, Boolean refunded) {
    }
}
