package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.SchedulerLockProperties;
import co.com.nequi.ticketing.model.lock.gateways.DistributedLock;
import co.com.nequi.ticketing.model.shared.gateways.ClockProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Lock with a lease stored in DynamoDB. Taking it is one conditional write, so two replicas never
 * hold it at the same time; a lock whose lease ended can be taken by another replica.
 */
@Slf4j
@Repository
public class DynamoDbLockRepository implements DistributedLock {

    static final String ACQUIRE_CONDITION = "attribute_not_exists(lockName) OR lockedUntilEpochMillis < :nowMillis "
            + "OR #owner = :me";
    static final String RELEASE_CONDITION = "#owner = :me";
    private static final String LOCK_NAME = "lockName";
    private static final String OWNER = "owner";
    private static final String LOCKED_UNTIL = "lockedUntilEpochMillis";

    private final DynamoDbAsyncClient client;
    private final SchedulerLockProperties properties;
    private final ClockProvider clockProvider;
    private final String owner;

    public DynamoDbLockRepository(DynamoDbAsyncClient client, SchedulerLockProperties properties,
                                  ClockProvider clockProvider, @Qualifier("instanceId") UUID instanceId) {
        this.client = client;
        this.properties = properties;
        this.clockProvider = clockProvider;
        this.owner = instanceId.toString();
    }

    @Override
    public Mono<Boolean> tryAcquire(String lockName, Duration lease) {
        return Mono.fromFuture(() -> {
                    Instant now = clockProvider.now();
                    log.debug("DynamoDB PutItem, table={}, lockName={}, operation=tryAcquire",
                            properties.schedulerLocksTable(), lockName);
                    return client.putItem(PutItemRequest.builder()
                            .tableName(properties.schedulerLocksTable())
                            .item(Map.of(
                                    LOCK_NAME, AttributeValue.fromS(lockName),
                                    OWNER, AttributeValue.fromS(owner),
                                    LOCKED_UNTIL, number(now.plus(lease).toEpochMilli())))
                            .conditionExpression(ACQUIRE_CONDITION)
                            .expressionAttributeNames(Map.of("#owner", OWNER))
                            .expressionAttributeValues(Map.of(
                                    ":nowMillis", number(now.toEpochMilli()),
                                    ":me", AttributeValue.fromS(owner)))
                            .build());
                })
                .thenReturn(Boolean.TRUE)
                .onErrorResume(ConditionalCheckFailedException.class, heldByOther -> Mono.just(Boolean.FALSE))
                .onErrorMap(DynamoDbLockRepository::toTechnicalException);
    }

    @Override
    public Mono<Void> release(String lockName) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB DeleteItem, table={}, lockName={}, operation=release",
                            properties.schedulerLocksTable(), lockName);
                    return client.deleteItem(DeleteItemRequest.builder()
                            .tableName(properties.schedulerLocksTable())
                            .key(Map.of(LOCK_NAME, AttributeValue.fromS(lockName)))
                            .conditionExpression(RELEASE_CONDITION)
                            .expressionAttributeNames(Map.of("#owner", OWNER))
                            .expressionAttributeValues(Map.of(":me", AttributeValue.fromS(owner)))
                            .build());
                })
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, takenByOther -> Mono.empty())
                .doOnError(error -> log.warn("Lock not released, it ends with its lease, lockName={}", lockName))
                .onErrorMap(DynamoDbLockRepository::toTechnicalException);
    }

    private static AttributeValue number(Long value) {
        return AttributeValue.fromN(String.valueOf(value));
    }

    private static Throwable toTechnicalException(Throwable error) {
        return error instanceof TechnicalException ? error : DYNAMODB_ERROR.build(error);
    }
}
