package co.com.nequi.ticketing.sqs.listener;

import co.com.nequi.ticketing.model.enums.PaymentResult;
import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.sqs.commons.PaymentConfirmedMessageCodec;
import co.com.nequi.ticketing.sqs.listener.config.PaymentConfirmedListenerProperties;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerProperties;
import co.com.nequi.ticketing.usecase.confirmpayment.ConfirmPaymentUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.ORDER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentConfirmedListenerTest {

    private static final String QUEUE_URL = "http://localhost/payment-confirmed";
    private static final String DLQ_URL = "http://localhost/payment-confirmed-dlq";

    @Mock
    private SqsAsyncClient client;

    @Mock
    private ConfirmPaymentUseCase confirmPaymentUseCase;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PaymentConfirmedMessageCodec codec = new PaymentConfirmedMessageCodec(JsonMapper.builder().build());

    @Test
    void shouldApplyAndDeleteNoticeWhenConfirmationCompletes() {
        // Given
        PaymentNotification approved = notice();
        PaymentNotification deferred = notice();
        givenQueues();
        givenMessages(List.of(message(deferred, "r-1"), message(approved, "r-2")));
        when(confirmPaymentUseCase.confirm(deferred)).thenReturn(Mono.just(ProcessingOutcome.DEFERRED));
        when(confirmPaymentUseCase.confirm(approved)).thenReturn(Mono.just(ProcessingOutcome.SOLD));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(listener().consume().take(1))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        verify(client).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE_URL).receiptHandle("r-2").build());
        verify(client, never()).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE_URL).receiptHandle("r-1")
                .build());
        assertThat(meterRegistry.get(PaymentConfirmedListener.OUTCOMES_METRIC).tag("result", "sold").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldMoveNoticeToDeadLetterQueueWhenOrderDoesNotExist() {
        // Given
        PaymentNotification missing = notice();
        PaymentNotification next = notice();
        givenQueues();
        givenMessages(List.of(message(missing, "r-1"), message(next, "r-2")));
        when(confirmPaymentUseCase.confirm(missing)).thenReturn(Mono.error(ORDER_NOT_FOUND.build()));
        when(confirmPaymentUseCase.confirm(next)).thenReturn(Mono.just(ProcessingOutcome.IGNORED));
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(listener().consume().take(1))
                .expectNext(ProcessingOutcome.IGNORED)
                .verifyComplete();
        verify(client).sendMessage(argThat((SendMessageRequest request) ->
                DLQ_URL.equals(request.queueUrl())
                        && "TKB0002".equals(request.messageAttributes().get("failureCode").stringValue())));
    }

    private void givenQueues() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class))).thenAnswer(invocation -> CompletableFuture.completedFuture(
                GetQueueUrlResponse.builder().queueUrl("http://localhost/" + ((GetQueueUrlRequest) invocation.getArgument(0))
                        .queueName()).build()));
    }

    private void givenMessages(List<Message> messages) {
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(messages).build()))
                .thenReturn(new CompletableFuture<>());
    }

    private PaymentConfirmedListener listener() {
        SqsListenerProperties properties = new SqsListenerProperties("us-east-1", null, "order-placed",
                "order-placed-dlq", Duration.ofSeconds(20), 10, 1);
        return new PaymentConfirmedListener(client, properties,
                new PaymentConfirmedListenerProperties("payment-confirmed", "payment-confirmed-dlq"), codec,
                confirmPaymentUseCase, new SqsErrorHandler(client, new SimpleMeterRegistry()), meterRegistry);
    }

    private Message message(PaymentNotification notification, String receiptHandle) {
        return Message.builder()
                .messageId(UUID.randomUUID().toString())
                .receiptHandle(receiptHandle)
                .body(codec.encode(notification))
                .build();
    }

    private static PaymentNotification notice() {
        return new PaymentNotification(UUID.randomUUID(), OrderId.newId(), PaymentResult.APPROVED,
                Instant.parse("2026-10-27T15:00:00Z"));
    }
}
