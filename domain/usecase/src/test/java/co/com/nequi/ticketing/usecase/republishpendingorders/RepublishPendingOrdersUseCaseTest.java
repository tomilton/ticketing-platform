package co.com.nequi.ticketing.usecase.republishpendingorders;

import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepublishPendingOrdersUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(5);
    private static final Integer CONCURRENCY = 2;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private ClockProvider clockProvider;

    private RepublishPendingOrdersUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RepublishPendingOrdersUseCase(orderRepository, orderEventPublisher, clockProvider,
                new OrderSettings(6, 6, Duration.ofMinutes(10), GRACE, CONCURRENCY));
        when(clockProvider.now()).thenReturn(NOW);
    }

    @Test
    void shouldPublishAndMarkEveryPendingOrderWhenOrdersAreOlderThanGrace() {
        // Given
        Order first = order();
        Order second = order();
        givenPending(first, second);
        when(orderEventPublisher.publishOrderPlaced(any(OrderPlacedMessage.class))).thenReturn(Mono.empty());
        when(orderRepository.markPublished(any(OrderId.class), any(Instant.class))).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.republish())
                .expectNext(2L)
                .verifyComplete();
        verify(orderEventPublisher).publishOrderPlaced(argThat(message -> message.orderId().equals(first.getOrderId())
                && message.eventId().equals(first.getEventId()) && message.quantity().value().equals(first.getQuantity())
                && message.occurredAt().equals(NOW)));
        verify(orderRepository).markPublished(first.getOrderId(), NOW);
        verify(orderRepository).markPublished(second.getOrderId(), NOW);
    }

    @Test
    void shouldContinueWithOtherOrdersWhenOnePublicationFails() {
        // Given
        Order failing = order();
        Order published = order();
        givenPending(failing, published);
        when(orderEventPublisher.publishOrderPlaced(argThat(message -> message != null
                && message.orderId().equals(failing.getOrderId()))))
                .thenReturn(Mono.error(new IllegalStateException("queue down")));
        when(orderEventPublisher.publishOrderPlaced(argThat(message -> message != null
                && message.orderId().equals(published.getOrderId()))))
                .thenReturn(Mono.empty());
        when(orderRepository.markPublished(published.getOrderId(), NOW)).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.republish())
                .expectNext(1L)
                .verifyComplete();
        verify(orderRepository, never()).markPublished(failing.getOrderId(), NOW);
    }

    @Test
    void shouldPublishAtMostConfiguredOrdersAtTheSameTimeWhenManyArePending() {
        // Given
        List<Order> orders = IntStream.range(0, 6).mapToObj(index -> order()).toList();
        givenPending(orders.toArray(Order[]::new));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(orderEventPublisher.publishOrderPlaced(any(OrderPlacedMessage.class))).thenAnswer(invocation ->
                Mono.delay(Duration.ofMillis(10))
                        .doOnSubscribe(subscription -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
                        .doOnTerminate(inFlight::decrementAndGet)
                        .then());
        when(orderRepository.markPublished(any(OrderId.class), any(Instant.class))).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.withVirtualTime(useCase::republish)
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(6L)
                .verifyComplete();
        assertThat(maxInFlight.get()).isEqualTo(CONCURRENCY);
    }

    @Test
    void shouldReturnZeroWhenThereAreNoPendingOrders() {
        // Given
        when(orderRepository.findPendingPublication(NOW.minus(GRACE))).thenReturn(Flux.empty());

        // When / Then
        StepVerifier.create(useCase.republish())
                .expectNext(0L)
                .verifyComplete();
    }

    private void givenPending(Order... orders) {
        when(orderRepository.findPendingPublication(NOW.minus(GRACE)))
                .thenReturn(Flux.fromArray(orders).map(Order::getOrderId));
        for (Order pending : orders) {
            when(orderRepository.findById(pending.getOrderId())).thenReturn(Mono.just(pending));
        }
    }

    private static Order order() {
        return Order.place(OrderId.newId(), new CustomerId(UUID.randomUUID()), EventId.newId(), new Quantity(1), 0,
                NOW.minusSeconds(30), Duration.ofMinutes(10));
    }
}
