package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.DynamoDbLockRepository;
import co.com.nequi.ticketing.dynamodb.config.SchedulerLockProperties;
import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.config.ComplimentarySettings;
import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.config.SweepSettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.command.GrantComplimentaryCommand;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.gateways.PaymentGateway;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import co.com.nequi.ticketing.usecase.grantcomplimentary.GrantComplimentaryUseCase;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SC-004 of feature 003: a grant moves exactly its quantity from available to complimentary, never
 * leaves negative available tickets and creates a final order that the sweep never touches.
 */
class ComplimentaryIntegrationTest {

    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(1);
    private static final ClockProvider CLOCK = Clock.systemUTC()::instant;
    private static final CustomerId ADMINISTRATOR_ID = new CustomerId(UUID.randomUUID());

    private static LocalStackRepositories repositories;
    private static GrantComplimentaryUseCase useCase;

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
        useCase = new GrantComplimentaryUseCase(repositories.idempotencyRepository(Duration.ofSeconds(10)),
                repositories.eventRepository(), repositories.orderRepository(), CLOCK, new ComplimentarySettings(50));
    }

    @Test
    void shouldMoveTicketsFromAvailableToComplimentaryWhenComplimentaryIsGranted() {
        // When
        Mono<Granted> granted = repositories.createEvent(10, 2).flatMap(event -> grant(event, 3)
                .flatMap(order -> repositories.orderRepository().findById(order.getOrderId())
                        .zipWith(inventory(event), Granted::new)));

        // Then
        StepVerifier.create(granted)
                .assertNext(result -> {
                    assertThat(result.order().getStatus()).isEqualTo(OrderStatus.COMPLIMENTARY);
                    assertThat(result.order().getTickets()).hasSize(3);
                    assertThat(result.inventory().available()).isEqualTo(7);
                    assertThat(result.inventory().complimentary()).isEqualTo(3);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldLeaveZeroAvailableWhenGrantTakesExactlyTheAvailableTickets() {
        // When
        Mono<EventInventory> inventory = repositories.createEvent(5, 1)
                .flatMap(event -> grant(event, 5).then(inventory(event)));

        // Then
        StepVerifier.create(inventory)
                .assertNext(result -> {
                    assertThat(result.available()).isZero();
                    assertThat(result.complimentary()).isEqualTo(5);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldFailWithSoldOutAndChangeNothingWhenGrantExceedsAvailableTickets() {
        // When
        Mono<EventInventory> inventory = repositories.createEvent(4, 2)
                .flatMap(event -> grant(event, 5)
                        .onErrorResume(BusinessException.class, error -> {
                            assertThat(error.getBusinessErrorMessage()).isEqualTo(BusinessErrorMessage.TICKETS_SOLD_OUT);
                            return Mono.empty();
                        })
                        .then(inventory(event)));

        // Then
        StepVerifier.create(inventory)
                .assertNext(result -> {
                    assertThat(result.available()).isEqualTo(4);
                    assertThat(result.complimentary()).isZero();
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldGrantWhenCustomerAlreadyHoldsTheMaximumOfTickets() {
        // Given
        CustomerId customerId = new CustomerId(UUID.randomUUID());
        PlaceOrderUseCase placeOrder = new PlaceOrderUseCase(repositories.idempotencyRepository(Duration.ofSeconds(10)),
                repositories.eventRepository(), repositories.orderRepository(), message -> Mono.empty(), CLOCK,
                new OrderSettings(6, 6, Duration.ofMinutes(10), Duration.ofSeconds(5), 4));

        // When
        Mono<Order> granted = repositories.createEvent(20, 1)
                .flatMap(event -> placeOrder.place(new PlaceOrderCommand(customerId,
                                new IdempotencyKey(UUID.randomUUID()), event.getEventId(), new Quantity(6)))
                        .then(useCase.grant(new GrantComplimentaryCommand(event.getEventId(), customerId,
                                new ComplimentaryQuantity(2), ADMINISTRATOR_ID, new IdempotencyKey(UUID.randomUUID())))));

        // Then
        StepVerifier.create(granted)
                .assertNext(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLIMENTARY))
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldKeepComplimentaryOrderWhenSweepRunsLater() {
        // Given
        ClockProvider later = () -> CLOCK.now().plus(Duration.ofDays(1));
        ExpireReservationsUseCase sweeper = new ExpireReservationsUseCase(new DynamoDbLockRepository(
                repositories.client(), new SchedulerLockProperties(LocalStackTestSupport.SCHEDULER_LOCKS_TABLE), later,
                UUID.randomUUID()), repositories.orderRepository(), new ProcessOrderUseCase(
                repositories.orderRepository(), repositories.eventRepository(), mock(PaymentGateway.class), later),
                later, new SweepSettings(Duration.ofSeconds(60), 500, 8));

        // When
        Mono<Granted> after = repositories.createEvent(10, 1).flatMap(event -> grant(event, 3)
                .flatMap(order -> sweeper.expire()
                        .then(repositories.orderRepository().findById(order.getOrderId()))
                        .zipWith(inventory(event), Granted::new)));

        // Then
        StepVerifier.create(after)
                .assertNext(result -> {
                    assertThat(result.order().getStatus()).isEqualTo(OrderStatus.COMPLIMENTARY);
                    assertThat(result.order().getVersion()).isEqualTo(1L);
                    assertThat(result.inventory().complimentary()).isEqualTo(3);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static Mono<Order> grant(Event event, Integer quantity) {
        return useCase.grant(command(event, new CustomerId(UUID.randomUUID()), quantity,
                new IdempotencyKey(UUID.randomUUID())));
    }

    private static GrantComplimentaryCommand command(Event event, CustomerId customerId, Integer quantity,
                                                     IdempotencyKey key) {
        return new GrantComplimentaryCommand(event.getEventId(), customerId, new ComplimentaryQuantity(quantity),
                ADMINISTRATOR_ID, key);
    }

    private static Mono<EventInventory> inventory(Event event) {
        return repositories.inventoryRepository().findShards(event.getEventId()).collectList()
                .map(shards -> EventInventory.aggregate(event.getEventId(), shards));
    }

    private record Granted(Order order, EventInventory inventory) {
    }
}
