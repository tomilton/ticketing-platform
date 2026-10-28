package co.com.nequi.ticketing.sqs.sender;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.payment.PaymentNotification;
import co.com.nequi.ticketing.model.payment.gateways.PaymentNotificationPublisher;
import co.com.nequi.ticketing.sqs.commons.PaymentConfirmedMessageCodec;
import co.com.nequi.ticketing.sqs.sender.config.PaymentConfirmedSenderProperties;
import co.com.nequi.ticketing.sqs.sender.config.SqsSenderClientConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.SQS_ERROR;

/**
 * Publishes the notices of the payment gateway to the queue the worker consumes. The message carries
 * its type and the order id as correlation id. A failed publication is propagated, so the API answers
 * an error and the gateway sends the notice again.
 */
@Slf4j
@Component
public class SqsPaymentNotificationPublisher implements PaymentNotificationPublisher {

    private static final String STRING_TYPE = "String";

    private final SqsAsyncClient client;
    private final PaymentConfirmedSenderProperties properties;
    private final PaymentConfirmedMessageCodec codec;
    private final Mono<String> queueUrl;

    public SqsPaymentNotificationPublisher(@Qualifier(SqsSenderClientConfig.SENDER_CLIENT) SqsAsyncClient client,
                                           PaymentConfirmedSenderProperties properties,
                                           PaymentConfirmedMessageCodec codec) {
        this.client = client;
        this.properties = properties;
        this.codec = codec;
        this.queueUrl = Mono.fromFuture(() -> client.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(properties.paymentConfirmedQueue())
                        .build()))
                .map(GetQueueUrlResponse::queueUrl)
                .cacheInvalidateIf(url -> false);
    }

    @Override
    public Mono<Void> publish(PaymentNotification notification) {
        return queueUrl
                .flatMap(url -> Mono.fromFuture(() -> {
                    log.debug("SQS SendMessage, queue={}, eventId={}, orderId={}", properties.paymentConfirmedQueue(),
                            notification.eventId(), notification.orderId().value());
                    return client.sendMessage(SendMessageRequest.builder()
                            .queueUrl(url)
                            .messageBody(codec.encode(notification))
                            .messageAttributes(Map.of(
                                    SqsOrderEventPublisher.TYPE_ATTRIBUTE, text(PaymentConfirmedMessageCodec.TYPE),
                                    SqsOrderEventPublisher.CORRELATION_ATTRIBUTE,
                                    text(notification.orderId().value().toString())))
                            .build());
                }))
                .timeout(properties.sendTimeout())
                .then()
                .onErrorMap(SqsPaymentNotificationPublisher::toTechnicalException);
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
