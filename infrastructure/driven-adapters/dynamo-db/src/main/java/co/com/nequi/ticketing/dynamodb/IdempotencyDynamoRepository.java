package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper;
import co.com.nequi.ticketing.model.config.IdempotencySettings;
import co.com.nequi.ticketing.model.enums.IdempotencyStatus;
import co.com.nequi.ticketing.model.idempotency.IdempotencyClaim;
import co.com.nequi.ticketing.model.idempotency.IdempotencyKey;
import co.com.nequi.ticketing.model.idempotency.IdempotencyRecord;
import co.com.nequi.ticketing.model.idempotency.IdempotencyScope;
import co.com.nequi.ticketing.model.idempotency.RequestFingerprint;
import co.com.nequi.ticketing.model.idempotency.gateways.IdempotencyRepository;
import co.com.nequi.ticketing.model.order.PlacementOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;
import static co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper.EXPIRES_AT;
import static co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper.IDEMPOTENCY_ID;
import static co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper.LOCKED_UNTIL;
import static co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper.LOCK_TOKEN;
import static co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper.REQUEST_HASH;
import static co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper.STATUS;

/**
 * Idempotency keys stored in DynamoDB in two phases: a request claims the key with a lease and the
 * key is closed with the definitive outcome. Closing or freeing a key requires that the same request
 * still holds it, so a closed key is never deleted. Times of the TTL are in seconds, as DynamoDB
 * requires, and the lease in milliseconds.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IdempotencyDynamoRepository implements IdempotencyRepository {

    static final String NEW_OR_EXPIRED_CONDITION = "attribute_not_exists(idempotencyId) OR expiresAtEpochSeconds < :nowSeconds";
    static final String EXPIRED_LEASE_CONDITION = "#status = :inProgress AND lockToken = :previousToken";
    static final String HELD_CONDITION = "#status = :inProgress AND lockToken = :token";
    private static final Map<String, String> STATUS_NAME = Map.of("#status", STATUS);
    private static final AttributeValue IN_PROGRESS = AttributeValue.fromS(IdempotencyStatus.IN_PROGRESS.name());

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final IdempotencyEntityMapper mapper;
    private final IdempotencySettings settings;

    @Override
    public Mono<IdempotencyClaim> claim(IdempotencyScope scope, IdempotencyKey key, RequestFingerprint fingerprint,
                                        Instant now) {
        return put(newClaim(scope, key, fingerprint, now), NEW_OR_EXPIRED_CONDITION,
                Map.of(":nowSeconds", AttributeValue.fromN(String.valueOf(now.getEpochSecond()))), Map.of())
                .onErrorResume(ConditionalCheckFailedException.class, taken -> read(scope, key)
                        .flatMap(stored -> reclaimIfLeaseEnded(stored, fingerprint, now)))
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<Void> complete(IdempotencyScope scope, IdempotencyKey key, UUID lockToken, PlacementOutcome outcome) {
        Map<String, AttributeValue> attributes = mapper.outcomeAttributes(outcome);
        Map<String, AttributeValue> values = new HashMap<>(heldValues(lockToken));
        attributes.forEach((name, value) -> values.put(":" + name, value));
        values.put(":completed", AttributeValue.fromS(IdempotencyStatus.COMPLETED.name()));
        String setOutcome = attributes.keySet().stream().sorted().map(name -> name + " = :" + name)
                .collect(Collectors.joining(", "));
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB UpdateItem, table={}, operation=complete", properties.idempotencyTable());
                    return client.updateItem(UpdateItemRequest.builder()
                            .tableName(properties.idempotencyTable())
                            .key(key(scope, key))
                            .updateExpression("SET #status = :completed, " + setOutcome)
                            .conditionExpression(HELD_CONDITION)
                            .expressionAttributeNames(STATUS_NAME)
                            .expressionAttributeValues(values)
                            .build());
                })
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, lost -> {
                    log.warn("Idempotency key no longer held, outcome not stored");
                    return Mono.empty();
                })
                .onErrorMap(this::toTechnicalException);
    }

    @Override
    public Mono<Void> release(IdempotencyScope scope, IdempotencyKey key, UUID lockToken) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB DeleteItem, table={}, operation=release", properties.idempotencyTable());
                    return client.deleteItem(DeleteItemRequest.builder()
                            .tableName(properties.idempotencyTable())
                            .key(key(scope, key))
                            .conditionExpression(HELD_CONDITION)
                            .expressionAttributeNames(STATUS_NAME)
                            .expressionAttributeValues(heldValues(lockToken))
                            .build());
                })
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, alreadyClosed -> {
                    log.debug("Idempotency key already closed or taken, nothing to release");
                    return Mono.empty();
                })
                .onErrorMap(this::toTechnicalException);
    }

    private Mono<IdempotencyClaim> reclaimIfLeaseEnded(IdempotencyRecord stored, RequestFingerprint fingerprint,
                                                       Instant now) {
        return Mono.just(stored)
                .filter(candidate -> candidate.status() == IdempotencyStatus.IN_PROGRESS
                        && !candidate.isLeaseActive(now) && candidate.matches(fingerprint))
                .flatMap(expired -> put(newClaim(expired.scope(), expired.key(), fingerprint, now),
                        EXPIRED_LEASE_CONDITION,
                        Map.of(":inProgress", IN_PROGRESS,
                                ":previousToken", AttributeValue.fromS(expired.lockToken().toString())),
                        STATUS_NAME)
                        .onErrorResume(ConditionalCheckFailedException.class, taken ->
                                read(expired.scope(), expired.key()).map(IdempotencyClaim.Existing::new)))
                .defaultIfEmpty(new IdempotencyClaim.Existing(stored));
    }

    private Mono<IdempotencyClaim> put(Map<String, AttributeValue> item, String condition,
                                       Map<String, AttributeValue> values, Map<String, String> names) {
        PutItemRequest.Builder request = PutItemRequest.builder()
                .tableName(properties.idempotencyTable())
                .item(item)
                .conditionExpression(condition)
                .expressionAttributeValues(values);
        if (!names.isEmpty()) {
            request.expressionAttributeNames(names);
        }
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB PutItem, table={}, operation=claim", properties.idempotencyTable());
                    return client.putItem(request.build());
                })
                .thenReturn(new IdempotencyClaim.Granted(UUID.fromString(item.get(LOCK_TOKEN).s())));
    }

    private Mono<IdempotencyRecord> read(IdempotencyScope scope, IdempotencyKey key) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB GetItem, table={}, operation=readClaim", properties.idempotencyTable());
                    return client.getItem(GetItemRequest.builder()
                            .tableName(properties.idempotencyTable())
                            .key(key(scope, key))
                            .consistentRead(Boolean.TRUE)
                            .build());
                })
                .retryWhen(DynamoDbRetryPolicy.retrySpec())
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> mapper.toDomain(response.item()))
                .switchIfEmpty(Mono.defer(() -> Mono.error(DYNAMODB_ERROR.build())));
    }

    private Map<String, AttributeValue> newClaim(IdempotencyScope scope, IdempotencyKey key,
                                                 RequestFingerprint fingerprint, Instant now) {
        return Map.of(
                IDEMPOTENCY_ID, AttributeValue.fromS(mapper.idempotencyId(scope, key)),
                REQUEST_HASH, AttributeValue.fromS(fingerprint.value()),
                STATUS, IN_PROGRESS,
                LOCK_TOKEN, AttributeValue.fromS(UUID.randomUUID().toString()),
                LOCKED_UNTIL, AttributeValue.fromN(String.valueOf(now.plus(settings.lease()).toEpochMilli())),
                EXPIRES_AT, AttributeValue.fromN(String.valueOf(now.plus(settings.ttl()).getEpochSecond())));
    }

    private Map<String, AttributeValue> key(IdempotencyScope scope, IdempotencyKey key) {
        return Map.of(IDEMPOTENCY_ID, AttributeValue.fromS(mapper.idempotencyId(scope, key)));
    }

    private static Map<String, AttributeValue> heldValues(UUID lockToken) {
        return Map.of(":inProgress", IN_PROGRESS, ":token", AttributeValue.fromS(lockToken.toString()));
    }

    private TechnicalException toTechnicalException(Throwable error) {
        return error instanceof TechnicalException technicalException
                ? technicalException
                : DYNAMODB_ERROR.build(error);
    }
}
