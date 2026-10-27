package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.support.LocalStackRepositories;
import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.inventory.EventInventory;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.command.PlaceOrderCommand;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-001 and US5-3: concurrent purchases never oversell an event nor exceed the limit of a customer.
 */
class PlaceOrderConcurrencyIntegrationTest {

    private static final Integer CAPACITY = 50;
    private static final Integer PURCHASES = 200;
    private static final String ACCEPTED = "ACCEPTED";
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(20);
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(2);

    private static LocalStackRepositories repositories;
    private static PlaceOrderUseCase useCase;

    @BeforeAll
    static void setUp() {
        repositories = new LocalStackRepositories();
        useCase = new PlaceOrderUseCase(repositories.idempotencyRepository(Duration.ofSeconds(10)),
                repositories.eventRepository(), repositories.orderRepository(), message -> Mono.empty(),
                Clock.systemUTC()::instant, new OrderSettings(6, 6, Duration.ofMinutes(10), Duration.ofSeconds(5), 4));
    }

    @Test
    void shouldAcceptExactlyCapacityWhenTwoHundredCustomersBuyFiftyTickets() {
        // When
        Mono<Outcome> outcome = repositories.createEvent(CAPACITY, 1).flatMap(event -> {
            Mono<List<String>> results = Flux.range(0, PURCHASES)
                    .flatMap(index -> purchase(new CustomerId(UUID.randomUUID()), event, 1), PURCHASES)
                    .collectList()
                    .cache();
            Mono<List<EventInventory>> samples = Flux.interval(SAMPLE_INTERVAL).onBackpressureDrop().onBackpressureDrop()
                    .concatMap(tick -> inventory(event))
                    .takeUntilOther(results)
                    .collectList();
            return Mono.zip(results, samples)
                    .flatMap(tuple -> inventory(event).map(last -> new Outcome(tuple.getT1(), tuple.getT2(), last)));
        });

        // Then
        StepVerifier.create(outcome)
                .assertNext(result -> {
                    assertThat(result.results()).filteredOn(ACCEPTED::equals).hasSize(CAPACITY);
                    assertThat(result.results()).filteredOn(BusinessErrorMessage.TICKETS_SOLD_OUT.getCode()::equals)
                            .hasSize(PURCHASES - CAPACITY);
                    assertThat(result.last()).isEqualTo(new EventInventory(result.last().eventId(), CAPACITY, 0,
                            CAPACITY, 0, 0));
                    assertThat(result.samples()).allSatisfy(sample -> {
                        assertThat(sample.available()).isNotNegative();
                        assertThat(sample.available() + sample.reserved() + sample.sold()).isEqualTo(CAPACITY);
                    });
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @Test
    void shouldAcceptOnlyTwoWhenSameCustomerBuysThreeTimesThreeAtOnce() {
        // Given
        CustomerId customerId = new CustomerId(UUID.randomUUID());

        // When
        Mono<List<String>> results = repositories.createEvent(CAPACITY, 1)
                .flatMap(event -> Flux.range(0, 3).flatMap(index -> purchase(customerId, event, 3)).collectList());

        // Then
        StepVerifier.create(results)
                .assertNext(list -> {
                    assertThat(list).filteredOn(ACCEPTED::equals).hasSize(2);
                    assertThat(list).filteredOn(BusinessErrorMessage.TICKET_LIMIT_EXCEEDED.getCode()::equals).hasSize(1);
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private static Mono<String> purchase(CustomerId customerId, Event event, Integer quantity) {
        return useCase.place(new PlaceOrderCommand(customerId, new IdempotencyKey(UUID.randomUUID()),
                        event.getEventId(), new Quantity(quantity)))
                .map(accepted -> ACCEPTED)
                .onErrorResume(BusinessException.class, error -> Mono.just(error.getBusinessErrorMessage().getCode()));
    }

    private static Mono<EventInventory> inventory(Event event) {
        return repositories.inventoryRepository().findShards(event.getEventId()).collectList()
                .map(shards -> EventInventory.aggregate(event.getEventId(), shards));
    }

    private record Outcome(List<String> results, List<EventInventory> samples, EventInventory last) {
    }
}
