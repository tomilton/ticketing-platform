package co.com.nequi.ticketing.sqs.listener;

import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.sqs.commons.OrderPlacedMessageCodec;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerProperties;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.ReceiverContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPlacedListenerTest {

    private static final String QUEUE_URL = "http://localhost/order-placed";
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Mock
    private SqsAsyncClient client;

    @Mock
    private ProcessOrderUseCase processOrderUseCase;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final OrderPlacedMessageCodec codec = new OrderPlacedMessageCodec(JsonMapper.builder().build());

    @Test
    void shouldProcessAndDeleteOnlyWhenProcessingCompletes() {
        // Given
        OrderPlacedMessage placed = placed();
        givenQueues();
        givenMessages(List.of(message(placed, "r-1")));
        when(processOrderUseCase.process(placed.orderId())).thenReturn(Mono.just(ProcessingOutcome.SOLD));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(listener(1, ObservationRegistry.NOOP).consume().take(1))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        verify(client).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE_URL).receiptHandle("r-1").build());
        assertThat(meterRegistry.get("ticketing.orders.outcomes").tag("result", "sold").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldKeepConsumingWithoutDeletingWhenProcessingFails() {
        // Given
        OrderPlacedMessage failing = placed();
        OrderPlacedMessage next = placed();
        givenQueues();
        givenMessages(List.of(message(failing, "r-1"), message(next, "r-2")));
        when(processOrderUseCase.process(failing.orderId()))
                .thenReturn(Mono.error(new IllegalStateException("gateway down")));
        when(processOrderUseCase.process(next.orderId())).thenReturn(Mono.just(ProcessingOutcome.CANCELLED));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(listener(1, ObservationRegistry.NOOP).consume().take(1))
                .expectNext(ProcessingOutcome.CANCELLED)
                .verifyComplete();
        verify(client, never()).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE_URL).receiptHandle("r-1")
                .build());
    }

    @Test
    void shouldRetryReceiveWithBackoffAndKeepConsumingWhenReceiveFails() {
        // Given
        OrderPlacedMessage placed = placed();
        givenQueues();
        when(client.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SqsException.builder().message("down").build()))
                .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder()
                        .messages(message(placed, "r-1")).build()))
                .thenReturn(new CompletableFuture<>());
        when(processOrderUseCase.process(placed.orderId())).thenReturn(Mono.just(ProcessingOutcome.SOLD));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        // When / Then
        StepVerifier.withVirtualTime(() -> listener(1, ObservationRegistry.NOOP).consume().take(1))
                .thenAwait(OrderPlacedListener.RECEIVE_MAX_BACKOFF)
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
    }

    @Test
    void shouldProcessAtMostMaxConcurrencyMessagesAtTheSameTime() {
        // Given
        List<OrderPlacedMessage> placed = IntStream.range(0, 6).mapToObj(index -> placed()).toList();
        givenQueues();
        givenMessages(placed.stream().map(message -> message(message, message.orderId().toString())).toList());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(processOrderUseCase.process(any(OrderId.class))).thenAnswer(invocation -> Mono.delay(Duration.ofMillis(10))
                .doOnSubscribe(subscription -> maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
                .doOnTerminate(inFlight::decrementAndGet)
                .thenReturn(ProcessingOutcome.SOLD));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        // When / Then
        StepVerifier.withVirtualTime(() -> listener(2, ObservationRegistry.NOOP).consume().take(6))
                .thenAwait(Duration.ofSeconds(1))
                .expectNextCount(6)
                .verifyComplete();
        assertThat(maxInFlight.get()).isEqualTo(2);
    }

    @Test
    void shouldRestoreTraceContextWhenMessageCarriesTraceparent() {
        // Given
        OrderPlacedMessage placed = placed();
        AtomicReference<String> restored = new AtomicReference<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new TraceparentReader(restored));
        givenQueues();
        givenMessages(List.of(message(placed, "r-1")));
        AtomicReference<Object> observationInContext = new AtomicReference<>();
        when(processOrderUseCase.process(placed.orderId())).thenReturn(Mono.deferContextual(context -> {
            observationInContext.set(context.getOrDefault(ObservationThreadLocalAccessor.KEY, null));
            return Mono.just(ProcessingOutcome.SOLD);
        }));
        when(client.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));

        // When / Then
        StepVerifier.create(listener(1, registry).consume().take(1))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        assertThat(restored.get()).isEqualTo(TRACEPARENT);
        assertThat(observationInContext.get()).isNotNull();
    }

    @Test
    void shouldCancelPendingReceiveWhenApplicationStops() {
        // Given
        CompletableFuture<ReceiveMessageResponse> pending = new CompletableFuture<>();
        givenQueues();
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(pending);
        OrderPlacedListener listener = listener(1, ObservationRegistry.NOOP);

        // When
        listener.run(new DefaultApplicationArguments());
        listener.stop();

        // Then
        assertThat(pending).isCancelled();
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

    private OrderPlacedListener listener(Integer maxConcurrency, ObservationRegistry registry) {
        SqsListenerProperties properties = new SqsListenerProperties("us-east-1", null, "order-placed",
                "order-placed-dlq", Duration.ofSeconds(20), 10, maxConcurrency);
        return new OrderPlacedListener(client, properties, codec, processOrderUseCase,
                new SqsErrorHandler(client, new SimpleMeterRegistry()), registry, meterRegistry);
    }

    private Message message(OrderPlacedMessage placed, String receiptHandle) {
        return Message.builder()
                .messageId(UUID.randomUUID().toString())
                .receiptHandle(receiptHandle)
                .body(codec.encode(placed))
                .messageAttributes(Map.of("traceparent",
                        MessageAttributeValue.builder().dataType("String").stringValue(TRACEPARENT).build()))
                .build();
    }

    private static OrderPlacedMessage placed() {
        return new OrderPlacedMessage(UUID.randomUUID(), OrderId.newId(), EventId.newId(), new Quantity(1),
                Instant.parse("2026-10-27T10:00:00Z"));
    }

    /**
     * Stands for the tracing handler of the application: it reads the trace context from the carrier.
     */
    private record TraceparentReader(AtomicReference<String> restored) implements ObservationHandler<ReceiverContext<Object>> {

        @Override
        public void onStart(ReceiverContext<Object> context) {
            restored.set(context.getGetter().get(context.getCarrier(), "traceparent"));
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ReceiverContext;
        }
    }
}
