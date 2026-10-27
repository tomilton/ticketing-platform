package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.OrderEntityMapper;
import co.com.nequi.ticketing.dynamodb.transaction.ComplimentaryTransactionBuilder;
import co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder;
import co.com.nequi.ticketing.dynamodb.transaction.ShardSoldOutException;
import co.com.nequi.ticketing.dynamodb.transaction.TransactionConflictException;
import co.com.nequi.ticketing.dynamodb.transaction.TransactionFailureTranslator;
import co.com.nequi.ticketing.dynamodb.transaction.TransitionTransactionBuilder;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderTransition;
import co.com.nequi.ticketing.model.order.ReservationContext;
import co.com.nequi.ticketing.model.order.gateways.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;
import static co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder.ORDER_SCHEMA;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.TICKETS_SOLD_OUT;

/**
 * Orders stored in DynamoDB. A reservation is one conditional transaction, so the inventory is
 * never oversold; a cancellation by a conflict is retried with backoff and the whole reservation
 * has a time limit shorter than the lease of the idempotency key.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderDynamoRepository implements OrderRepository {

    private static final String ORDER_ID = "orderId";
    private static final Duration CONFLICT_FIRST_BACKOFF = Duration.ofMillis(20);
    private static final Duration CONFLICT_MAX_BACKOFF = Duration.ofMillis(500);
    private static final double CONFLICT_JITTER = 0.5;
    private static final String MARK_PUBLISHED_UPDATE = "REMOVE publishBucket SET publishedAt = :nowMillis";
    private static final String ORDER_EXISTS_CONDITION = "attribute_exists(orderId)";
    private static final String PENDING_CONDITION = "publishBucket = :bucket AND createdAt < :before";
    private static final String EXPIRED_CONDITION = "activeBucket = :bucket AND expiresAt <= :nowMillis";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final OrderEntityMapper orderMapper;
    private final ReservationTransactionBuilder reservationBuilder;
    private final TransitionTransactionBuilder transitionBuilder;
    private final TransactionFailureTranslator failureTranslator;
    private final ComplimentaryTransactionBuilder complimentaryBuilder;

    @Override
    public Mono<Order> reserve(Order order, Event event, ReservationContext context, Instant now) {
        int shardCount = event.getShardCount();
        return Flux.range(0, shardCount)
                .map(offset -> order.toBuilder().shardId((order.getShardId() + offset) % shardCount).build())
                .concatMap(candidate -> reserveOnShard(candidate, event, context, now)
                        .onErrorResume(ShardSoldOutException.class, soldOut -> Mono.empty()))
                .next()
                .switchIfEmpty(Mono.defer(() -> Mono.error(TICKETS_SOLD_OUT.build())))
                .timeout(properties.reserveTimeout())
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<Order> findById(OrderId orderId) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB GetItem, table={}, orderId={}", properties.ordersTable(), orderId.value());
                    return client.getItem(GetItemRequest.builder()
                            .tableName(properties.ordersTable())
                            .key(key(orderId))
                            .consistentRead(Boolean.TRUE)
                            .build());
                })
                .retryWhen(DynamoDbRetryPolicy.retrySpec())
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> orderMapper.toDomain(ORDER_SCHEMA.mapToItem(response.item())))
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<Order> apply(OrderTransition transition, InventoryMovement movement) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB TransactWriteItems, table={}, orderId={}, from={}, to={}, movement={}",
                            properties.ordersTable(), transition.previous().getOrderId().value(),
                            transition.previous().getStatus(), transition.next().getStatus(), movement);
                    return client.transactWriteItems(transitionBuilder.build(transition, movement));
                })
                .thenReturn(transition.next())
                .onErrorMap(TransactionCanceledException.class, failureTranslator::translateTransition)
                .retryWhen(conflictRetry())
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<Void> markPublished(OrderId orderId, Instant at) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB UpdateItem, table={}, orderId={}, operation=markPublished",
                            properties.ordersTable(), orderId.value());
                    return client.updateItem(UpdateItemRequest.builder()
                            .tableName(properties.ordersTable())
                            .key(key(orderId))
                            .updateExpression(MARK_PUBLISHED_UPDATE)
                            .conditionExpression(ORDER_EXISTS_CONDITION)
                            .expressionAttributeValues(Map.of(":nowMillis",
                                    AttributeValue.fromN(String.valueOf(at.toEpochMilli()))))
                            .build());
                })
                .retryWhen(DynamoDbRetryPolicy.retrySpec())
                .doOnError(error -> log.warn("Order not marked as published, orderId={}", orderId.value()))
                .then()
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Flux<OrderId> findPendingPublication(Instant createdBefore) {
        return Flux.range(0, ReservationTransactionBuilder.BUCKETS)
                .map(bucket -> ReservationTransactionBuilder.PUBLISH_BUCKET_PREFIX + bucket)
                .flatMap(bucket -> {
                    log.debug("DynamoDB Query, table={}, index={}, bucket={}", properties.ordersTable(),
                            properties.pendingPublicationIndex(), bucket);
                    return Flux.from(client.queryPaginator(QueryRequest.builder()
                            .tableName(properties.ordersTable())
                            .indexName(properties.pendingPublicationIndex())
                            .keyConditionExpression(PENDING_CONDITION)
                            .expressionAttributeValues(Map.of(
                                    ":bucket", AttributeValue.fromS(bucket),
                                    ":before", AttributeValue.fromN(String.valueOf(createdBefore.toEpochMilli()))))
                            .build()).items());
                })
                .map(item -> new OrderId(UUID.fromString(item.get(ORDER_ID).s())))
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<Order> grantComplimentary(Order order, Event event, Instant now) {
        int shardCount = event.getShardCount();
        return Flux.range(0, shardCount)
                .map(offset -> order.toBuilder().shardId((order.getShardId() + offset) % shardCount).build())
                .concatMap(candidate -> grantOnShard(candidate, event, now)
                        .onErrorResume(ShardSoldOutException.class, soldOut -> Mono.empty()))
                .next()
                .switchIfEmpty(Mono.defer(() -> Mono.error(TICKETS_SOLD_OUT.build())))
                .timeout(properties.reserveTimeout())
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Flux<OrderId> findExpired(Instant now, Integer limit) {
        return Flux.range(0, ReservationTransactionBuilder.BUCKETS)
                .map(bucket -> ReservationTransactionBuilder.ACTIVE_BUCKET_PREFIX + bucket)
                .flatMap(bucket -> {
                    log.debug("DynamoDB Query, table={}, index={}, bucket={}", properties.ordersTable(),
                            properties.expiringReservationsIndex(), bucket);
                    return Flux.from(client.queryPaginator(QueryRequest.builder()
                            .tableName(properties.ordersTable())
                            .indexName(properties.expiringReservationsIndex())
                            .keyConditionExpression(EXPIRED_CONDITION)
                            .expressionAttributeValues(Map.of(
                                    ":bucket", AttributeValue.fromS(bucket),
                                    ":nowMillis", AttributeValue.fromN(String.valueOf(now.toEpochMilli()))))
                            .limit(limit)
                            .build()).items());
                })
                .take(limit)
                .map(item -> new OrderId(UUID.fromString(item.get(ORDER_ID).s())))
                .onErrorMap(this::toTechnicalException);
    }

    private Mono<Order> grantOnShard(Order order, Event event, Instant now) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB TransactWriteItems, table={}, orderId={}, eventId={}, shardId={}, "
                                    + "operation=grantComplimentary", properties.ordersTable(), order.getOrderId().value(),
                            event.getEventId().value(), order.getShardId());
                    return client.transactWriteItems(complimentaryBuilder.build(order, event, now));
                })
                .thenReturn(order)
                .onErrorMap(TransactionCanceledException.class, failureTranslator::translateGrant)
                .retryWhen(conflictRetry());
    }

    private Mono<Order> reserveOnShard(Order order, Event event, ReservationContext context, Instant now) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB TransactWriteItems, table={}, orderId={}, eventId={}, shardId={}",
                            properties.ordersTable(), order.getOrderId().value(), event.getEventId().value(),
                            order.getShardId());
                    return client.transactWriteItems(reservationBuilder.build(order, event, context, now));
                })
                .thenReturn(order)
                .onErrorMap(TransactionCanceledException.class, failureTranslator::translateReservation)
                .retryWhen(conflictRetry());
    }

    private Retry conflictRetry() {
        return Retry.backoff(properties.transactionConflictRetries(), CONFLICT_FIRST_BACKOFF)
                .maxBackoff(CONFLICT_MAX_BACKOFF)
                .jitter(CONFLICT_JITTER)
                .filter(TransactionConflictException.class::isInstance);
    }

    static Map<String, AttributeValue> key(OrderId orderId) {
        return Map.of(ORDER_ID, AttributeValue.fromS(orderId.value().toString()));
    }

    private Throwable toTechnicalException(Throwable error) {
        return error instanceof BusinessException || error instanceof TechnicalException
                ? error
                : DYNAMODB_ERROR.build(error);
    }
}
