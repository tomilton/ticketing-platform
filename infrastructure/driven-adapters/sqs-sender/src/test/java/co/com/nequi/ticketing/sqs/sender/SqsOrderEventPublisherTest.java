package co.com.nequi.ticketing.sqs.sender;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.sqs.commons.OrderPlacedMessageCodec;
import co.com.nequi.ticketing.sqs.sender.config.SqsSenderProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SqsOrderEventPublisherTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/order-placed";
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final OrderPlacedMessage MESSAGE = new OrderPlacedMessage(UUID.randomUUID(), OrderId.newId(),
            EventId.newId(), new Quantity(2), Instant.parse("2026-10-27T10:00:00Z"));

    @Mock
    private SqsAsyncClient client;

    private final OrderPlacedMessageCodec codec = new OrderPlacedMessageCodec(JsonMapper.builder().build());

    @Test
    void shouldSendBodyWithTypeCorrelationAndTraceWhenOrderIsPublished() {
        // Given
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        givenQueueUrl();
        when(client.sendMessage(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(publisher(tracingRegistry()).publishOrderPlaced(MESSAGE))
                .verifyComplete();
        SendMessageRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(codec.decode(request.messageBody())).isEqualTo(MESSAGE);
        assertThat(request.messageAttributes().get("type").stringValue()).isEqualTo("OrderPlaced");
        assertThat(request.messageAttributes().get("correlationId").stringValue())
                .isEqualTo(MESSAGE.orderId().value().toString());
        assertThat(request.messageAttributes().get("traceparent").stringValue()).isEqualTo(TRACEPARENT);
    }

    @Test
    void shouldSendWithoutTraceWhenThereIsNoTraceContext() {
        // Given
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        givenQueueUrl();
        when(client.sendMessage(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(publisher(ObservationRegistry.NOOP).publishOrderPlaced(MESSAGE))
                .verifyComplete();
        assertThat(captor.getValue().messageAttributes()).doesNotContainKey("traceparent");
    }

    @Test
    void shouldFailWithSqsErrorAndLogOrderWhenSdkFails(CapturedOutput output) {
        // Given
        givenQueueUrl();
        when(client.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SqsException.builder().message("unavailable").build()));

        // When / Then
        StepVerifier.create(publisher(ObservationRegistry.NOOP).publishOrderPlaced(MESSAGE))
                .expectErrorSatisfies(SqsOrderEventPublisherTest::assertSqsError)
                .verify();
        assertThat(output).contains("Order not published").contains(MESSAGE.orderId().value().toString());
    }

    @Test
    void shouldFailWithSqsErrorWhenPublicationIsSlowerThanItsTimeout(CapturedOutput output) {
        // Given
        givenQueueUrl();
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(new CompletableFuture<>());

        // When / Then
        StepVerifier.withVirtualTime(() -> publisher(ObservationRegistry.NOOP).publishOrderPlaced(MESSAGE))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(SqsOrderEventPublisherTest::assertSqsError)
                .verify();
        assertThat(output).contains("Order not published");
    }

    private void givenQueueUrl() {
        when(client.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetQueueUrlResponse.builder().queueUrl(QUEUE_URL).build()));
    }

    private SqsOrderEventPublisher publisher(ObservationRegistry registry) {
        return new SqsOrderEventPublisher(client, new SqsSenderProperties("us-east-1", null, "order-placed",
                Duration.ofSeconds(1)), codec, registry);
    }

    private static ObservationRegistry tracingRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new TraceparentInjector());
        return registry;
    }

    private static void assertSqsError(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.SQS_ERROR);
    }

    /**
     * Stands for the tracing handler of the application: it writes the trace context in the carrier.
     */
    private static final class TraceparentInjector implements ObservationHandler<SenderContext<Object>> {

        @Override
        public void onStart(SenderContext<Object> context) {
            context.getSetter().set(context.getCarrier(), "traceparent", TRACEPARENT);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof SenderContext;
        }
    }
}
