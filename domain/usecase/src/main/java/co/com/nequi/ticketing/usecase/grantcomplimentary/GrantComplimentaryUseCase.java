package co.com.nequi.ticketing.usecase.grantcomplimentary;

import co.com.nequi.ticketing.model.config.ComplimentarySettings;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.command.GrantComplimentaryCommand;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_ACTIVE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKET_LIMIT_EXCEEDED;

/**
 * Gives complimentary tickets of an event on sale to a customer. The tickets leave the available
 * ones in the same write that creates the final complimentary order, and they do not count for the
 * limit of the customer.
 */
@RequiredArgsConstructor
public class GrantComplimentaryUseCase {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final ClockProvider clockProvider;
    private final ComplimentarySettings complimentarySettings;

    /**
     * Grants the complimentary tickets.
     *
     * @param command the event, the customer and the number of tickets
     * @return the complimentary order; it fails with {@code TICKET_LIMIT_EXCEEDED} above the
     * configured maximum, {@code EVENT_NOT_FOUND}, {@code EVENT_NOT_ACTIVE} or {@code TICKETS_SOLD_OUT}
     */
    public Mono<Order> grant(GrantComplimentaryCommand command) {
        return Mono.just(command)
                .filter(candidate -> candidate.quantity().value() <= complimentarySettings.maxPerGrant())
                .switchIfEmpty(Mono.defer(() -> Mono.error(TICKET_LIMIT_EXCEEDED.build())))
                .flatMap(valid -> eventRepository.findById(valid.eventId()))
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_FOUND.build())))
                .flatMap(event -> Mono.fromSupplier(clockProvider::now)
                        .filter(event::isListable)
                        .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_ACTIVE.build())))
                        .flatMap(now -> orderRepository.grantComplimentary(newOrder(command, event, now), event, now)));
    }

    private static Order newOrder(GrantComplimentaryCommand command, Event event, Instant now) {
        Integer firstShard = ThreadLocalRandom.current().nextInt(event.getShardCount());
        return Order.grantComplimentary(OrderId.newId(), command.customerId(), command.eventId(), command.quantity(),
                firstShard, now);
    }
}
