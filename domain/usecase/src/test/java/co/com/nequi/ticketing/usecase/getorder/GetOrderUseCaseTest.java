package co.com.nequi.ticketing.usecase.getorder;

import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.TicketStatus;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.Ticket;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.request.GetOrderQuery;
import co.com.nequi.ticketing.model.shared.AuthenticatedUser;
import co.com.nequi.ticketing.model.shared.CustomerId;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetOrderUseCaseTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-27T10:00:00Z");
    private static final CustomerId OWNER = new CustomerId(UUID.randomUUID());
    private static final AuthenticatedUser OWNER_CALLER = new AuthenticatedUser(OWNER, Boolean.FALSE);
    private static final AuthenticatedUser OTHER_CUSTOMER =
            new AuthenticatedUser(new CustomerId(UUID.randomUUID()), Boolean.FALSE);
    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser(new CustomerId(UUID.randomUUID()), Boolean.TRUE);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ClockProvider clockProvider;

    private GetOrderUseCase useCase;

    private Order order;

    @BeforeEach
    void setUp() {
        useCase = new GetOrderUseCase(orderRepository, clockProvider);
        order = Order.place(OrderId.newId(), OWNER, EventId.newId(), new Quantity(2), 0, CREATED_AT,
                Duration.ofMinutes(10));
    }

    @Test
    void shouldReturnViewWithEffectiveStatusWhenOrderBelongsToCustomer() {
        // Given
        when(orderRepository.findById(order.getOrderId())).thenReturn(Mono.just(order));
        when(clockProvider.now()).thenReturn(CREATED_AT.plusSeconds(60));

        // When / Then
        StepVerifier.create(useCase.get(new GetOrderQuery(OWNER_CALLER, order.getOrderId())))
                .assertNext(view -> {
                    assertThat(view.order()).isSameAs(order);
                    assertThat(view.effectiveStatus()).isEqualTo(OrderStatus.RESERVED);
                    assertThat(view.tickets()).extracting(Ticket::status).containsExactly(TicketStatus.RESERVED,
                            TicketStatus.RESERVED);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnExpiredWithAvailableTicketsWhenReservationEnded() {
        // Given
        when(orderRepository.findById(order.getOrderId())).thenReturn(Mono.just(order));
        when(clockProvider.now()).thenReturn(order.getExpiresAt());

        // When / Then
        StepVerifier.create(useCase.get(new GetOrderQuery(OWNER_CALLER, order.getOrderId())))
                .assertNext(view -> {
                    assertThat(view.effectiveStatus()).isEqualTo(OrderStatus.EXPIRED);
                    assertThat(view.tickets()).extracting(Ticket::status).containsOnly(TicketStatus.AVAILABLE);
                })
                .verifyComplete();
    }

    @Test
    void shouldFailWithOrderNotFoundWhenOrderDoesNotExist() {
        // Given
        when(orderRepository.findById(order.getOrderId())).thenReturn(Mono.empty());

        // When / Then
        StepVerifier.create(useCase.get(new GetOrderQuery(OWNER_CALLER, order.getOrderId())))
                .expectErrorSatisfies(GetOrderUseCaseTest::assertOrderNotFound)
                .verify();
        verifyNoInteractions(clockProvider);
    }

    @Test
    void shouldFailWithOrderNotFoundWhenOrderBelongsToAnotherCustomer() {
        // Given
        when(orderRepository.findById(order.getOrderId())).thenReturn(Mono.just(order));

        // When / Then
        StepVerifier.create(useCase.get(new GetOrderQuery(OTHER_CUSTOMER, order.getOrderId())))
                .expectErrorSatisfies(GetOrderUseCaseTest::assertOrderNotFound)
                .verify();
    }

    @Test
    void shouldReturnViewWhenAdministratorReadsOrderOfAnotherCustomer() {
        // Given
        when(orderRepository.findById(order.getOrderId())).thenReturn(Mono.just(order));
        when(clockProvider.now()).thenReturn(CREATED_AT.plusSeconds(60));

        // When / Then
        StepVerifier.create(useCase.get(new GetOrderQuery(ADMIN, order.getOrderId())))
                .assertNext(view -> {
                    assertThat(view.order()).isSameAs(order);
                    assertThat(view.effectiveStatus()).isEqualTo(OrderStatus.RESERVED);
                })
                .verifyComplete();
    }

    private static void assertOrderNotFound(Throwable error) {
        assertThat(error).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                .isEqualTo(BusinessErrorMessage.ORDER_NOT_FOUND);
    }
}
