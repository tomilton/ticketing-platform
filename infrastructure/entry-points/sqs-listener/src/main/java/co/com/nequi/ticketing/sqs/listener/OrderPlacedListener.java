package co.com.nequi.ticketing.sqs.listener;

import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.sqs.commons.OrderPlacedMessageCodec;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerClientConfig;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerProperties;
import co.com.nequi.ticketing.usecase.processorder.ProcessOrderUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.ReceiverContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Consumer of the accepted orders, only in the {@code worker} role. Each message is processed by the
 * current status of its order and deleted only when the processing completes; a failure is decided
 * by {@link SqsErrorHandler}. A failed receive never ends the consumption: it is retried with
 * backoff.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class OrderPlacedListener implements ApplicationRunner {

    static final Duration RECEIVE_FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration RECEIVE_MAX_BACKOFF = Duration.ofSeconds(30);
    private static final String OBSERVATION = "ticketing.orders.process";
    private static final String ALL_ATTRIBUTES = "All";
    static final String OUTCOMES_METRIC = "ticketing.orders.outcomes";
    private static final String RESULT_TAG = "result";

    private final SqsAsyncClient client;
    private final SqsListenerProperties properties;
    private final OrderPlacedMessageCodec codec;
    private final ProcessOrderUseCase processOrderUseCase;
    private final SqsErrorHandler errorHandler;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Mono<Tuple2<String, String>> queueUrls;
    private Disposable subscription;

    public OrderPlacedListener(@Qualifier(SqsListenerClientConfig.LISTENER_CLIENT) SqsAsyncClient client,
                               SqsListenerProperties properties, OrderPlacedMessageCodec codec,
                               ProcessOrderUseCase processOrderUseCase, SqsErrorHandler errorHandler,
                               ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        this.client = client;
        this.properties = properties;
        this.codec = codec;
        this.processOrderUseCase = processOrderUseCase;
        this.errorHandler = errorHandler;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.queueUrls = Mono.zip(queueUrl(properties.orderPlacedQueue()), queueUrl(properties.orderPlacedDlq()))
                .cacheInvalidateIf(urls -> false);
    }

    /**
     * Starts the only long lived subscription of the application. This is the exception of the
     * reactive principle: the framework does not subscribe to a queue consumer, so it is subscribed
     * once at startup and disposed at shutdown.
     *
     * @param arguments the arguments of the application
     */
    @Override
    public void run(ApplicationArguments arguments) {
        subscription = consume().subscribe();
    }

    @PreDestroy
    public void stop() {
        Optional.ofNullable(subscription).ifPresent(Disposable::dispose);
    }

    Flux<ProcessingOutcome> consume() {
        return Flux.defer(() -> queueUrls.flatMapMany(urls -> receive(urls.getT1())))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, RECEIVE_FIRST_BACKOFF)
                        .maxBackoff(RECEIVE_MAX_BACKOFF)
                        .doBeforeRetry(signal -> log.error("SQS receive failed, queue={}, attempt={}",
                                properties.orderPlacedQueue(), signal.totalRetries() + 1, signal.failure())))
                .repeat()
                .flatMap(this::handle, properties.maxConcurrency())
                .doOnNext(this::countOutcome);
    }

    private Flux<Message> receive(String queueUrl) {
        return Mono.fromFuture(() -> {
                    log.debug("SQS ReceiveMessage, queue={}", properties.orderPlacedQueue());
                    return client.receiveMessage(ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(properties.maxMessages())
                            .waitTimeSeconds((int) properties.waitTime().toSeconds())
                            .messageAttributeNames(ALL_ATTRIBUTES)
                            .build());
                })
                .flatMapIterable(ReceiveMessageResponse::messages);
    }

    private Mono<ProcessingOutcome> handle(Message message) {
        return queueUrls.flatMap(urls -> {
            Observation observation = receiverObservation(message);
            return Mono.fromCallable(() -> codec.decode(message.body()))
                    .flatMap(placed -> processOrderUseCase.process(placed.orderId()))
                    .flatMap(outcome -> errorHandler.delete(message, urls.getT1()).thenReturn(outcome))
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                    .doOnError(observation::error)
                    .doFinally(signal -> observation.stop())
                    .onErrorResume(error -> errorHandler.handle(error, message, urls.getT1(), urls.getT2())
                            .then(Mono.empty()));
        });
    }

    private void countOutcome(ProcessingOutcome outcome) {
        Optional.of(outcome)
                .filter(counted -> counted != ProcessingOutcome.IGNORED)
                .ifPresent(counted -> meterRegistry.counter(OUTCOMES_METRIC, RESULT_TAG,
                        counted.name().toLowerCase(Locale.ROOT)).increment());
    }

    private Observation receiverObservation(Message message) {
        ReceiverContext<Message> context = new ReceiverContext<>((carrier, key) ->
                Optional.ofNullable(carrier.messageAttributes().get(key)).map(MessageAttributeValue::stringValue)
                        .orElse(null));
        context.setCarrier(message);
        return Observation.createNotStarted(OBSERVATION, () -> context, observationRegistry).start();
    }

    private Mono<String> queueUrl(String queueName) {
        return Mono.fromFuture(() -> client.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()))
                .map(GetQueueUrlResponse::queueUrl);
    }
}
