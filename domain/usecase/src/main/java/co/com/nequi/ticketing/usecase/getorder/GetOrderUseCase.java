package co.com.nequi.ticketing.usecase.getorder;

import co.com.nequi.ticketing.model.order.OrderView;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.order.request.GetOrderQuery;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;

/**
 * Gets an order of the customer who asks, with its effective status and tickets at the instant of
 * the query. The order of another customer is answered as if it did not exist.
 */
@RequiredArgsConstructor
public class GetOrderUseCase {

    private final OrderRepository orderRepository;
    private final ClockProvider clockProvider;

    public Mono<OrderView> get(GetOrderQuery query) {
        return orderRepository.findById(query.orderId())
                .filter(order -> order.isOwnedBy(query.customerId()))
                .switchIfEmpty(Mono.defer(() -> Mono.error(ORDER_NOT_FOUND.build())))
                .map(order -> {
                    var now = clockProvider.now();
                    return new OrderView(order, order.effectiveStatus(now), order.ticketsAt(now));
                });
    }
}
