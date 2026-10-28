package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.OrderDynamoRepository;
import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorGateway;
import co.com.nequi.ticketing.paymentsimulator.PaymentSimulatorProperties;
import co.com.nequi.ticketing.usecase.confirmpayment.ConfirmPaymentUseCase;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-003 of feature 004: three copies of an approved notice of the gateway and the charge in line of
 * the worker compete for the same pending order; only one transition wins, the inventory moves once
 * and the audit keeps a single sale.
 */
class PaymentNoticeRaceIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Integer NOTICE_COPIES = 3;

    private static LocalStackRepositories repositories;
    private static OrderDynamoRepository orderRepository;

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
        orderRepository = repositories.orderRepository();
    }

    @Test
    void shouldSellOnceAndMoveInventoryOnceWhenNoticesAndChargeCompete() {
        // Given
        Instant now = Instant.now();
        ClockProvider clock = () -> now.plusSeconds(1);
        PaymentSimulatorGateway payments = new PaymentSimulatorGateway(new PaymentSimulatorProperties(
                PaymentSimulatorProperties.Mode.APPROVE, Duration.ZERO, Duration.ofSeconds(2), 0.5, Duration.ofSeconds(5)));
        ConfirmPaymentUseCase confirm = new ConfirmPaymentUseCase(orderRepository, payments, clock);
        ProcessOrderUseCase charge = new ProcessOrderUseCase(orderRepository, repositories.eventRepository(), payments,
                clock);

        // When
        Mono<String> result = repositories.createEvent(10, 1)
                .flatMap(event -> pending(event, now))
                .flatMap(order -> Flux.range(0, NOTICE_COPIES)
                        .map(copy -> confirm.confirm(new PaymentNotification(UUID.randomUUID(), order.getOrderId(),
                                PaymentResult.APPROVED, now)).subscribeOn(Schedulers.parallel()))
                        .concatWithValues(charge.process(order.getOrderId()).subscribeOn(Schedulers.parallel()))
                        .flatMap(attempt -> attempt)
                        .then(Mono.defer(() -> orderRepository.findById(order.getOrderId())
                                .zipWith(inventory(order), (stored, inventory) -> stored.getStatus() + "/"
                                        + inventory.available() + "/" + inventory.sold())
                                .zipWith(auditRecords(order.getOrderId()), (summary, records) -> summary + "/" + records))));

        // Then
        StepVerifier.create(result)
                .assertNext(summary -> assertThat(summary).isEqualTo(OrderStatus.SOLD + "/8/2/3"))
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static Mono<Order> pending(Event event, Instant now) {
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(UUID.randomUUID());
        Order order = Order.place(OrderId.newId(), customerId, event.getEventId(), new Quantity(2), 0, now, TTL);
        return repositories.idempotencyRepository(Duration.ofSeconds(10))
                .claim(IdempotencyScope.ofCustomer(customerId), key,
                        RequestFingerprint.of(event.getEventId(), new Quantity(order.getQuantity())), now)
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

    private static Mono<Integer> auditRecords(OrderId orderId) {
        return Mono.fromFuture(() -> repositories.client().query(QueryRequest.builder()
                        .tableName(LocalStackTestSupport.ORDER_AUDIT_TABLE)
                        .keyConditionExpression("orderId = :orderId")
                        .expressionAttributeValues(Map.of(":orderId", AttributeValue.fromS(orderId.value().toString())))
                        .consistentRead(Boolean.TRUE)
                        .build()))
                .map(response -> response.count());
    }
}
