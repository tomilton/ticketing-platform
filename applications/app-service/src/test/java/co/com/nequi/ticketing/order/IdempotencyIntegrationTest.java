package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.OrderDynamoRepository;
import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderTransition;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-003 and US4: a repeated purchase with the same key returns the original outcome and never
 * reserves twice; a technical error frees the key.
 */
class IdempotencyIntegrationTest {

    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(1);
    private static final Integer REPETITIONS = 10;

    private static LocalStackRepositories repositories;
    private static OrderDynamoRepository orderRepository;

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
        orderRepository = repositories.orderRepository();
    }

    @Test
    void shouldReturnSameOrderAndReserveOnceWhenSameKeyIsRepeatedInSequence() {
        // Given
        PlaceOrderUseCase useCase = useCase(orderRepository);
        CustomerId customerId = customer();
        IdempotencyKey key = key();

        // When
        Mono<Result> result = repositories.createEvent(10, 1).flatMap(event -> Flux.range(0, REPETITIONS)
                .concatMap(index -> outcome(useCase, customerId, key, event))
                .collectList()
                .flatMap(outcomes -> inventory(event).map(inventory -> new Result(outcomes, inventory))));

        // Then
        StepVerifier.create(result)
                .assertNext(found -> {
                    assertThat(found.outcomes()).hasSize(REPETITIONS).containsOnly(found.outcomes().getFirst());
                    assertThat(found.inventory().reserved()).isEqualTo(2);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldReserveOnceAndAnswerOriginalOrInProgressWhenSameKeyIsRepeatedAtOnce() {
        // Given
        PlaceOrderUseCase useCase = useCase(orderRepository);
        CustomerId customerId = customer();
        IdempotencyKey key = key();

        // When
        Mono<Result> result = repositories.createEvent(10, 1).flatMap(event -> Flux.range(0, REPETITIONS)
                .flatMap(index -> outcome(useCase, customerId, key, event), REPETITIONS)
                .collectList()
                .flatMap(outcomes -> inventory(event).map(inventory -> new Result(outcomes, inventory))));

        // Then
        StepVerifier.create(result)
                .assertNext(found -> {
                    assertThat(found.outcomes().stream().filter(text -> text.startsWith("ACCEPTED")).distinct())
                            .hasSize(1);
                    assertThat(found.outcomes()).allMatch(text -> text.startsWith("ACCEPTED")
                            || text.equals(BusinessErrorMessage.REQUEST_IN_PROGRESS.getCode()));
                    assertThat(found.inventory().reserved()).isEqualTo(2);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldCreateOneOrderPerCustomerWhenTwoCustomersUseSameKey() {
        // Given
        PlaceOrderUseCase useCase = useCase(orderRepository);
        IdempotencyKey key = key();

        // When
        Mono<Result> result = repositories.createEvent(10, 1).flatMap(event -> Flux.just(customer(), customer())
                .concatMap(customerId -> outcome(useCase, customerId, key, event))
                .collectList()
                .flatMap(outcomes -> inventory(event).map(inventory -> new Result(outcomes, inventory))));

        // Then
        StepVerifier.create(result)
                .assertNext(found -> {
                    assertThat(found.outcomes()).doesNotHaveDuplicates().allMatch(text -> text.startsWith("ACCEPTED"));
                    assertThat(found.inventory().reserved()).isEqualTo(4);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldRepeatSameSoldOutWhenRejectedPurchaseIsRepeated() {
        // Given
        PlaceOrderUseCase useCase = useCase(orderRepository);
        CustomerId customerId = customer();
        IdempotencyKey key = key();

        // When
        Mono<List<String>> outcomes = repositories.createEvent(1, 1)
                .flatMap(event -> outcome(useCase, customer(), key(), event, 1)
                        .thenMany(Flux.range(0, 2).concatMap(index -> outcome(useCase, customerId, key, event)))
                        .collectList());

        // Then
        StepVerifier.create(outcomes)
                .assertNext(found -> assertThat(found)
                        .containsExactly(BusinessErrorMessage.TICKETS_SOLD_OUT.getCode(),
                                BusinessErrorMessage.TICKETS_SOLD_OUT.getCode()))
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldFreeKeySoRetryTriesAgainWhenReservationFailsTechnically() {
        // Given
        AtomicBoolean failed = new AtomicBoolean();
        PlaceOrderUseCase useCase = useCase(failingOnce(failed, reserved -> Mono.error(new IllegalStateException("down"))));
        CustomerId customerId = customer();
        IdempotencyKey key = key();

        // When
        Mono<String> retried = repositories.createEvent(10, 1)
                .flatMap(event -> outcome(useCase, customerId, key, event).then(outcome(useCase, customerId, key, event)));

        // Then
        StepVerifier.create(retried)
                .assertNext(found -> assertThat(found).startsWith("ACCEPTED"))
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldReturnOriginalOrderWhenTimeoutHappensAfterTheReservationWasStored() {
        // Given
        AtomicBoolean failed = new AtomicBoolean();
        PlaceOrderUseCase useCase = useCase(failingOnce(failed, reserved -> reserved.then(Mono.error(new TimeoutException()))));
        CustomerId customerId = customer();
        IdempotencyKey key = key();

        // When
        Mono<Result> result = repositories.createEvent(10, 1).flatMap(event -> Flux.range(0, 2)
                .concatMap(index -> outcome(useCase, customerId, key, event))
                .collectList()
                .flatMap(outcomes -> inventory(event).map(inventory -> new Result(outcomes, inventory))));

        // Then
        StepVerifier.create(result)
                .assertNext(found -> {
                    assertThat(found.outcomes().getLast()).startsWith("ACCEPTED");
                    assertThat(found.inventory().reserved()).isEqualTo(2);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static PlaceOrderUseCase useCase(OrderRepository repository) {
        return new PlaceOrderUseCase(repositories.idempotencyRepository(Duration.ofSeconds(10)),
                repositories.eventRepository(), repository, message -> Mono.empty(), Clock.systemUTC()::instant,
                new OrderSettings(6, 6, Duration.ofMinutes(10), Duration.ofSeconds(5), 4));
    }

    private static OrderRepository failingOnce(AtomicBoolean failed, UnaryOperator<Mono<Order>> failure) {
        return new OrderRepository() {
            @Override
            public Mono<Order> reserve(Order order, Event event, ReservationContext context, Instant now) {
                Mono<Order> reserved = orderRepository.reserve(order, event, context, now);
                return failed.compareAndSet(false, true) ? failure.apply(reserved) : reserved;
            }

            @Override
            public Mono<Order> findById(OrderId orderId) {
                return orderRepository.findById(orderId);
            }

            @Override
            public Mono<Order> apply(OrderTransition transition, InventoryMovement movement) {
                return orderRepository.apply(transition, movement);
            }

            @Override
            public Mono<Void> markPublished(OrderId orderId, Instant at) {
                return orderRepository.markPublished(orderId, at);
            }

            @Override
            public Flux<OrderId> findPendingPublication(Instant createdBefore) {
                return orderRepository.findPendingPublication(createdBefore);
            }
        };
    }

    private static Mono<String> outcome(PlaceOrderUseCase useCase, CustomerId customerId, IdempotencyKey key,
                                        Event event) {
        return outcome(useCase, customerId, key, event, 2);
    }

    private static Mono<String> outcome(PlaceOrderUseCase useCase, CustomerId customerId, IdempotencyKey key,
                                        Event event, Integer quantity) {
        return useCase.place(new PlaceOrderCommand(customerId, key, event.getEventId(), new Quantity(quantity)))
                .map(accepted -> "ACCEPTED " + accepted.orderId().value() + " " + accepted.expiresAt())
                .onErrorResume(BusinessException.class, error -> Mono.just(error.getBusinessErrorMessage().getCode()))
                .onErrorResume(error -> !(error instanceof BusinessException), error -> Mono.just("TECHNICAL"));
    }

    private static Mono<EventInventory> inventory(Event event) {
        return repositories.inventoryRepository().findShards(event.getEventId()).collectList()
                .map(shards -> EventInventory.aggregate(event.getEventId(), shards));
    }

    private static CustomerId customer() {
        return new CustomerId(UUID.randomUUID());
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey(UUID.randomUUID());
    }

    private record Result(List<String> outcomes, EventInventory inventory) {
    }
}
