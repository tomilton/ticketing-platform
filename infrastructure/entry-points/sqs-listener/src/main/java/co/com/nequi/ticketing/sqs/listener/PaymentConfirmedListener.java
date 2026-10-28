package co.com.nequi.ticketing.sqs.listener;

import co.com.nequi.ticketing.model.enums.ProcessingOutcome;
import co.com.nequi.ticketing.sqs.commons.PaymentConfirmedMessageCodec;
import co.com.nequi.ticketing.sqs.listener.config.PaymentConfirmedListenerProperties;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerClientConfig;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerProperties;
import co.com.nequi.ticketing.usecase.confirmpayment.ConfirmPaymentUseCase;
import io.micrometer.core.instrument.MeterRegistry;
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
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.Locale;
import java.util.Optional;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.PAYMENT_GATEWAY_ERROR;

/**
 * Consumer of the notices of the payment gateway, only in the {@code worker} role. Each notice is
 * applied by the current status of its order and deleted only when it completes. A notice that
 * arrived before the payment was requested is left in the queue as a retryable error, so SQS delivers
 * it again; any other failure is decided by {@link SqsErrorHandler}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class PaymentConfirmedListener implements ApplicationRunner {

    static final String OUTCOMES_METRIC = "ticketing.payments.notices";
    private static final String RESULT_TAG = "result";
    private static final String ALL_ATTRIBUTES = "All";

    private final SqsAsyncClient client;
    private final SqsListenerProperties properties;
    private final PaymentConfirmedListenerProperties queues;
    private final PaymentConfirmedMessageCodec codec;
    private final ConfirmPaymentUseCase confirmPaymentUseCase;
    private final SqsErrorHandler errorHandler;
    private final MeterRegistry meterRegistry;
    private final Mono<Tuple2<String, String>> queueUrls;
    private Disposable subscription;

    public PaymentConfirmedListener(@Qualifier(SqsListenerClientConfig.LISTENER_CLIENT) SqsAsyncClient client,
                                    SqsListenerProperties properties, PaymentConfirmedListenerProperties queues,
                                    PaymentConfirmedMessageCodec codec, ConfirmPaymentUseCase confirmPaymentUseCase,
                                    SqsErrorHandler errorHandler, MeterRegistry meterRegistry) {
        this.client = client;
        this.properties = properties;
        this.queues = queues;
        this.codec = codec;
        this.confirmPaymentUseCase = confirmPaymentUseCase;
        this.errorHandler = errorHandler;
        this.meterRegistry = meterRegistry;
        this.queueUrls = Mono.zip(queueUrl(queues.paymentConfirmedQueue()), queueUrl(queues.paymentConfirmedDlq()))
                .cacheInvalidateIf(urls -> false);
    }

    /**
     * Starts the long lived subscription of the queue, the same documented exception of the reactive
     * principle as the consumer of the accepted orders: subscribed once at startup, disposed at shutdown.
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
                .retryWhen(Retry.backoff(Long.MAX_VALUE, OrderPlacedListener.RECEIVE_FIRST_BACKOFF)
                        .maxBackoff(OrderPlacedListener.RECEIVE_MAX_BACKOFF)
                        .doBeforeRetry(signal -> log.error("SQS receive failed, queue={}, attempt={}",
                                queues.paymentConfirmedQueue(), signal.totalRetries() + 1, signal.failure())))
                .repeat()
                .flatMap(this::handle, properties.maxConcurrency())
                .doOnNext(outcome -> meterRegistry.counter(OUTCOMES_METRIC, RESULT_TAG,
                        outcome.name().toLowerCase(Locale.ROOT)).increment());
    }

    private Flux<Message> receive(String queueUrl) {
        return Mono.fromFuture(() -> {
                    log.debug("SQS ReceiveMessage, queue={}", queues.paymentConfirmedQueue());
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
        return queueUrls.flatMap(urls -> Mono.fromCallable(() -> codec.decode(message.body()))
                .flatMap(confirmPaymentUseCase::confirm)
                .filter(outcome -> outcome != ProcessingOutcome.DEFERRED)
                .switchIfEmpty(Mono.defer(() -> Mono.error(PAYMENT_GATEWAY_ERROR.build())))
                .flatMap(outcome -> errorHandler.delete(message, urls.getT1()).thenReturn(outcome))
                .onErrorResume(error -> errorHandler.handle(error, message, urls.getT1(), urls.getT2())
                        .then(Mono.empty())));
    }

    private Mono<String> queueUrl(String queueName) {
        return Mono.fromFuture(() -> client.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()))
                .map(GetQueueUrlResponse::queueUrl);
    }
}
