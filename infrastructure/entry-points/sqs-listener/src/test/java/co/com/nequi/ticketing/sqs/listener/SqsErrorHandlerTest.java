package co.com.nequi.ticketing.sqs.listener;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsErrorHandlerTest {

    private static final String QUEUE = "http://localhost/queue";
    private static final String DLQ = "http://localhost/dlq";
    private static final Message MESSAGE = Message.builder().messageId("m-1").receiptHandle("r-1").body("{}").build();

    @Mock
    private SqsAsyncClient client;

    private SimpleMeterRegistry meterRegistry;

    private SqsErrorHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new SqsErrorHandler(client, meterRegistry);
    }

    @Test
    void shouldDeleteWithoutDeadLetterWhenAnotherCopyWonTheRace() {
        // Given
        givenDeleteSucceeds();

        // When / Then
        StepVerifier.create(handler.handle(BusinessErrorMessage.INVALID_STATE_TRANSITION.build(), MESSAGE, QUEUE, DLQ))
                .verifyComplete();
        verify(client, never()).sendMessage(any(SendMessageRequest.class));
        assertThat(meterRegistry.find(SqsErrorHandler.DEAD_LETTERED_METRIC).counter()).isNull();
    }

    @Test
    void shouldMoveToDeadLetterWithCodeWhenOrderDoesNotExist() {
        // Given
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        when(client.sendMessage(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
        givenDeleteSucceeds();

        // When / Then
        StepVerifier.create(handler.handle(BusinessErrorMessage.ORDER_NOT_FOUND.build(), MESSAGE, QUEUE, DLQ))
                .verifyComplete();
        assertThat(captor.getValue().queueUrl()).isEqualTo(DLQ);
        assertThat(captor.getValue().messageBody()).isEqualTo("{}");
        assertThat(captor.getValue().messageAttributes().get("failureCode").stringValue()).isEqualTo("TKB0002");
        assertThat(meterRegistry.get(SqsErrorHandler.DEAD_LETTERED_METRIC).tag("reason", "TKB0002").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldMoveToDeadLetterWhenMessageIsInvalid() {
        // Given
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
        givenDeleteSucceeds();

        // When / Then
        StepVerifier.create(handler.handle(TechnicalErrorMessage.INVALID_MESSAGE.build(), MESSAGE, QUEUE, DLQ))
                .verifyComplete();
        assertThat(meterRegistry.get(SqsErrorHandler.DEAD_LETTERED_METRIC).tag("reason", "TKT0008").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldLeaveMessageForNewDeliveryWhenErrorIsRetryable() {
        // When / Then
        StepVerifier.create(handler.handle(TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR.build(), MESSAGE, QUEUE, DLQ))
                .verifyComplete();
        StepVerifier.create(handler.handle(new IllegalStateException("unknown"), MESSAGE, QUEUE, DLQ))
                .verifyComplete();
        verifyNoInteractions(client);
    }

    @Test
    void shouldFailWithSqsErrorWhenDeleteFails() {
        // Given
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("queue down")));

        // When / Then
        StepVerifier.create(handler.delete(MESSAGE, QUEUE))
                .expectErrorMatches(error -> error.getMessage().equals("Message queue error"))
                .verify();
    }

    private void givenDeleteSucceeds() {
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
    }
}
