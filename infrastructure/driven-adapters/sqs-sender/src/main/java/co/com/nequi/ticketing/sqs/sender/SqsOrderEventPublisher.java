package co.com.nequi.ticketing.sqs.sender;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.order.gateways.OrderEventPublisher;
import co.com.nequi.ticketing.sqs.commons.OrderPlacedMessageCodec;
import co.com.nequi.ticketing.sqs.sender.config.SqsSenderClientConfig;
import co.com.nequi.ticketing.sqs.sender.config.SqsSenderProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.SenderContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.SQS_ERROR;

/**
 * Publishes the accepted orders to SQS. The message carries the trace context and the order id as
 * correlation id. A failed publication is logged with the order id and propagated; the purchase
 * absorbs it and the republication sends the order later.
 */
@Slf4j
@Component
public class SqsOrderEventPublisher implements OrderEventPublisher {

    public static final String TYPE_ATTRIBUTE = "type";
    public static final String TRACEPARENT_ATTRIBUTE = "traceparent";
    public static final String CORRELATION_ATTRIBUTE = "correlationId";
    private static final String OBSERVATION = "ticketing.orders.publish";
    private static final String STRING_TYPE = "String";

    private final SqsAsyncClient client;
    private final SqsSenderProperties properties;
    private final OrderPlacedMessageCodec codec;
    private final ObservationRegistry observationRegistry;
    private final Mono<String> queueUrl;

    public SqsOrderEventPublisher(@Qualifier(SqsSenderClientConfig.SENDER_CLIENT) SqsAsyncClient client,
                                  SqsSenderProperties properties, OrderPlacedMessageCodec codec,
                                  ObservationRegistry observationRegistry) {
        this.client = client;
        this.properties = properties;
        this.codec = codec;
        this.observationRegistry = observationRegistry;
        this.queueUrl = Mono.fromFuture(() -> client.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(properties.orderPlacedQueue())
                        .build()))
                .map(GetQueueUrlResponse::queueUrl)
                .cacheInvalidateIf(url -> false);
    }

    @Override
    public Mono<Void> publishOrderPlaced(OrderPlacedMessage message) {
        return Mono.deferContextual(view -> {
                    Map<String, String> carrier = new HashMap<>();
                    SenderContext<Map<String, String>> context = new SenderContext<>(Map::put);
                    context.setCarrier(carrier);
                    Observation observation = Observation.createNotStarted(OBSERVATION, () -> context, observationRegistry)
                            .parentObservation(view.getOrDefault(ObservationThreadLocalAccessor.KEY, null))
                            .start();
                    return queueUrl
                            .flatMap(url -> Mono.fromFuture(() -> {
                                log.debug("SQS SendMessage, queue={}, orderId={}", properties.orderPlacedQueue(),
                                        message.orderId().value());
                                return client.sendMessage(request(url, message, carrier));
                            }))
                            .timeout(properties.sendTimeout())
                            .doOnError(observation::error)
                            .doFinally(signal -> observation.stop());
                })
                .doOnError(error -> log.warn("Order not published, orderId={}, queue={}", message.orderId().value(),
                        properties.orderPlacedQueue()))
                .then()
                .onErrorMap(SqsOrderEventPublisher::toTechnicalException);
    }

    private SendMessageRequest request(String url, OrderPlacedMessage message, Map<String, String> carrier) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(TYPE_ATTRIBUTE, text(OrderPlacedMessageCodec.TYPE));
        attributes.put(CORRELATION_ATTRIBUTE, text(message.orderId().value().toString()));
        Optional.ofNullable(carrier.get(TRACEPARENT_ATTRIBUTE))
                .ifPresent(traceparent -> attributes.put(TRACEPARENT_ATTRIBUTE, text(traceparent)));
        return SendMessageRequest.builder()
                .queueUrl(url)
                .messageBody(codec.encode(message))
                .messageAttributes(attributes)
                .build();
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType(STRING_TYPE).stringValue(value).build();
    }

    private static TechnicalException toTechnicalException(Throwable error) {
        return error instanceof TechnicalException technicalException
                ? technicalException
                : SQS_ERROR.build(error);
    }
}
