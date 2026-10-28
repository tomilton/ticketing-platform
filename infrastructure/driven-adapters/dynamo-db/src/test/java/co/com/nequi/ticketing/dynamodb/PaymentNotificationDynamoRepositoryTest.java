package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.WebhookEventsProperties;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentNotificationDynamoRepositoryTest {

    private static final String TABLE = "payment_webhook_events";
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-03T15:00:00Z");
    private static final PaymentNotification NOTIFICATION = new PaymentNotification(UUID.randomUUID(),
            OrderId.newId(), PaymentResult.APPROVED, Instant.parse("2026-10-27T15:00:00Z"));

    @Mock
    private DynamoDbAsyncClient client;

    private PaymentNotificationDynamoRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PaymentNotificationDynamoRepository(client, new WebhookEventsProperties(TABLE));
    }

    @Test
    void shouldReportNoticeAsKnownWhenItemExists() {
        // Given
        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        when(client.getItem(captor.capture())).thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder()
                .item(Map.of("eventId", AttributeValue.fromS(NOTIFICATION.eventId().toString()))).build()));

        // When / Then
        StepVerifier.create(repository.exists(NOTIFICATION.eventId()))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
        assertThat(captor.getValue().tableName()).isEqualTo(TABLE);
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void shouldReportNoticeAsNewWhenItemDoesNotExist() {
        // Given
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.exists(NOTIFICATION.eventId()))
                .expectNext(Boolean.FALSE)
                .verifyComplete();
    }

    @Test
    void shouldWriteNoticeOnlyIfNewWithItsExpirationWhenRegistered() {
        // Given
        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        when(client.putItem(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.register(NOTIFICATION, EXPIRES_AT)).verifyComplete();
        assertThat(captor.getValue().conditionExpression()).isEqualTo("attribute_not_exists(eventId)");
        assertThat(captor.getValue().item())
                .containsEntry("eventId", AttributeValue.fromS(NOTIFICATION.eventId().toString()))
                .containsEntry("orderId", AttributeValue.fromS(NOTIFICATION.orderId().value().toString()))
                .containsEntry("status", AttributeValue.fromS("APPROVED"))
                .containsEntry("expiresAtEpochSeconds", AttributeValue.fromN(String.valueOf(EXPIRES_AT.getEpochSecond())));
    }

    @Test
    void shouldCompleteWhenNoticeIsAlreadyRegistered() {
        // Given
        when(client.putItem(any(PutItemRequest.class))).thenReturn(CompletableFuture.failedFuture(
                ConditionalCheckFailedException.builder().message("exists").build()));

        // When / Then
        StepVerifier.create(repository.register(NOTIFICATION, EXPIRES_AT)).verifyComplete();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenReadFails() {
        // Given
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));

        // When / Then
        StepVerifier.create(repository.exists(NOTIFICATION.eventId()))
                .expectErrorSatisfies(error -> assertThat(((TechnicalException) error).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR))
                .verify();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenWriteFails() {
        // Given
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));

        // When / Then
        StepVerifier.create(repository.register(NOTIFICATION, EXPIRES_AT))
                .expectErrorSatisfies(error -> assertThat(((TechnicalException) error).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR))
                .verify();
    }
}
