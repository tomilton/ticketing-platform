package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.WebhookEventsProperties;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.payment.gateways.PaymentNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.DYNAMODB_ERROR;

/**
 * Register of the received notices of the payment gateway in DynamoDB, keyed by the identifier the
 * gateway gives to each notice. A notice is written only if it does not exist, so registering a copy
 * changes nothing. The TTL of DynamoDB deletes the register when it expires.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PaymentNotificationDynamoRepository implements PaymentNotificationRepository {

    static final String EVENT_ID = "eventId";
    static final String NEW_NOTICE_CONDITION = "attribute_not_exists(eventId)";

    private final DynamoDbAsyncClient client;
    private final WebhookEventsProperties properties;

    @Override
    public Mono<Boolean> exists(UUID eventId) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB GetItem, table={}, eventId={}", properties.webhookEventsTable(), eventId);
                    return client.getItem(GetItemRequest.builder()
                            .tableName(properties.webhookEventsTable())
                            .key(Map.of(EVENT_ID, AttributeValue.fromS(eventId.toString())))
                            .consistentRead(Boolean.TRUE)
                            .build());
                })
                .map(response -> response.hasItem() && !response.item().isEmpty())
                .onErrorMap(PaymentNotificationDynamoRepository::toTechnicalException);
    }

    @Override
    public Mono<Void> register(PaymentNotification notification, Instant expiresAt) {
        return Mono.fromFuture(() -> {
                    log.debug("DynamoDB PutItem, table={}, eventId={}, orderId={}", properties.webhookEventsTable(),
                            notification.eventId(), notification.orderId().value());
                    return client.putItem(PutItemRequest.builder()
                            .tableName(properties.webhookEventsTable())
                            .item(Map.of(
                                    EVENT_ID, AttributeValue.fromS(notification.eventId().toString()),
                                    "orderId", AttributeValue.fromS(notification.orderId().value().toString()),
                                    "status", AttributeValue.fromS(notification.status().name()),
                                    "occurredAtEpochMillis", AttributeValue.fromN(String.valueOf(
                                            notification.occurredAt().toEpochMilli())),
                                    "expiresAtEpochSeconds", AttributeValue.fromN(String.valueOf(
                                            expiresAt.getEpochSecond()))))
                            .conditionExpression(NEW_NOTICE_CONDITION)
                            .build());
                })
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, copy -> {
                    log.debug("Payment notice already registered, eventId={}", notification.eventId());
                    return Mono.empty();
                })
                .onErrorMap(PaymentNotificationDynamoRepository::toTechnicalException);
    }

    private static TechnicalException toTechnicalException(Throwable error) {
        return error instanceof TechnicalException technicalException
                ? technicalException
                : DYNAMODB_ERROR.build(error);
    }
}
