package co.com.nequi.ticketing.usecase.grantcomplimentary;

import co.com.nequi.ticketing.model.config.ComplimentarySettings;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.event.gateways.EventRepository;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.nequi.ticketing.model.order.GrantContext;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.command.GrantComplimentaryCommand;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import co.com.nequi.ticketing.usecase.idempotency.IdempotencyGuard;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_ACTIVE;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.EVENT_NOT_FOUND;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKET_LIMIT_EXCEEDED;

/**
 * Gives complimentary tickets of an event on sale to a customer. The tickets leave the available
 * ones in the same write that creates the final complimentary order and closes the idempotency key
 * of the administrator, and they do not count for the limit of the customer. A repetition with the
 * same key and content gets the same order without granting again.
 */
@RequiredArgsConstructor
public class GrantComplimentaryUseCase {

    private final IdempotencyRepository idempotencyRepository;
    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final ClockProvider clockProvider;
    private final ComplimentarySettings complimentarySettings;

    /**
     * Grants the complimentary tickets.
     *
     * @param command the event, the customer, the number of tickets, the administrator and the key
     * @return the complimentary order; it fails with {@code TICKET_LIMIT_EXCEEDED} above the
     * configured maximum, {@code EVENT_NOT_FOUND}, {@code EVENT_NOT_ACTIVE}, {@code TICKETS_SOLD_OUT},
     * {@code IDEMPOTENCY_KEY_REUSED} or {@code REQUEST_IN_PROGRESS}
     */
    public Mono<Order> grant(GrantComplimentaryCommand command) {
        RequestFingerprint fingerprint = RequestFingerprint.ofGrant(command.eventId(), command.customerId(),
                command.quantity());
        IdempotencyScope scope = IdempotencyScope.ofAdministrator(command.administratorId());
        return Mono.fromSupplier(clockProvider::now)
                .flatMap(now -> idempotencyRepository.claim(scope, command.key(), fingerprint, now)
                        .flatMap(claim -> switch (claim) {
                            case IdempotencyClaim.Granted granted -> IdempotencyGuard.settleOnFailure(
                                    grantClaimed(command, new GrantContext(command.administratorId(), scope,
                                            command.key(), granted.lockToken()), now),
                                    idempotencyRepository, scope, command.key(), granted.lockToken());
                            case IdempotencyClaim.Existing existing -> IdempotencyGuard.repeat(existing.record(),
                                    fingerprint, accepted -> orderRepository.findById(accepted.orderId())
                                            .switchIfEmpty(Mono.defer(() -> Mono.error(ORDER_NOT_FOUND.build()))));
                        }));
    }

    private Mono<Order> grantClaimed(GrantComplimentaryCommand command, GrantContext context, Instant now) {
        return Mono.just(command)
                .filter(candidate -> candidate.quantity().value() <= complimentarySettings.maxPerGrant())
                .switchIfEmpty(Mono.defer(() -> Mono.error(TICKET_LIMIT_EXCEEDED.build())))
                .flatMap(valid -> eventRepository.findById(valid.eventId()))
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_FOUND.build())))
                .filter(event -> event.isListable(now))
                .switchIfEmpty(Mono.defer(() -> Mono.error(EVENT_NOT_ACTIVE.build())))
                .flatMap(event -> orderRepository.grantComplimentary(newOrder(command, event, now), event, context, now));
    }

    private static Order newOrder(GrantComplimentaryCommand command, Event event, Instant now) {
        Integer firstShard = ThreadLocalRandom.current().nextInt(event.getShardCount());
        return Order.grantComplimentary(OrderId.newId(), command.customerId(), command.eventId(), command.quantity(),
                firstShard, now);
    }
}
