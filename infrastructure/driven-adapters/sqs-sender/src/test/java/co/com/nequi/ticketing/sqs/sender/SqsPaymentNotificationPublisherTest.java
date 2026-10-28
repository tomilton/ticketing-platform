package co.com.nequi.ticketing.sqs.sender;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.sqs.commons.PaymentConfirmedMessageCodec;
import co.com.nequi.ticketing.sqs.sender.config.PaymentConfirmedSenderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsPaymentNotificationPublisherTest {

    private static final String QUEUE = "payment-confirmed";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/payment-confirmed";
    private static final PaymentNotification NOTIFICATION = new PaymentNotification(UUID.randomUUID(),
            OrderId.newId(), PaymentResult.DECLINED, Instant.parse("2026-10-27T15:00:00Z"));

    @Mock
    private SqsAsyncClient client;

    private final PaymentConfirmedMessageCodec codec = new PaymentConfirmedMessageCodec(JsonMapper.builder().build());
    private SqsPaymentNotificationPublisher publisher;

    @BeforeEach
    void setUp() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
        publisher = new SqsPaymentNotificationPublisher(client,
                new PaymentConfirmedSenderProperties(QUEUE, Duration.ofSeconds(2)), codec);
    }

    @Test
    void shouldSendNoticeWithTypeAndCorrelationWhenPublished() {
        // Given
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        when(client.sendMessage(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(publisher.publish(NOTIFICATION)).verifyComplete();
        SendMessageRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(codec.decode(request.messageBody())).isEqualTo(NOTIFICATION);
        assertThat(request.messageAttributes().get("type").stringValue()).isEqualTo("PaymentConfirmed");
        assertThat(request.messageAttributes().get("correlationId").stringValue())
                .isEqualTo(NOTIFICATION.orderId().value().toString());
    }

    @Test
    void shouldFailWithSqsErrorWhenSendFails() {
        // Given
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));

        // When / Then
        StepVerifier.create(publisher.publish(NOTIFICATION))
                .expectErrorSatisfies(error -> assertThat(((TechnicalException) error).getErrorMessage())
                        .isEqualTo(TechnicalErrorMessage.SQS_ERROR))
                .verify();
    }
}
