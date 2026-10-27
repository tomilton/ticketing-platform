package co.com.nequi.ticketing.sqs.listener;

import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.sqs.listener.config.SqsListenerClientConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;

import static co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage.SQS_ERROR;
import static co.com.nequi.ticketing.model.enums.BusinessErrorMessage.INVALID_STATE_TRANSITION;

/**
 * Decides what happens to a message whose processing failed. A lost race between copies is
 * deleted; a permanent error is moved to the dead letter queue at once, because retrying it never
 * changes the result; any other error leaves the message in the queue so SQS delivers it again and,
 * after the last attempt, moves it to the dead letter queue.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class SqsErrorHandler {

    public static final String DEAD_LETTERED_METRIC = "ticketing.orders.messages.dead_lettered";
    public static final String FAILURE_CODE_ATTRIBUTE = "failureCode";
    private static final String REASON_TAG = "reason";
    private static final String STRING_TYPE = "String";

    private final SqsAsyncClient client;
    private final MeterRegistry meterRegistry;

    public SqsErrorHandler(@Qualifier(SqsListenerClientConfig.LISTENER_CLIENT) SqsAsyncClient client,
                           MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Handles the failure of one message.
     *
     * @param error    the error of the processing
     * @param message  the message
     * @param queueUrl the queue the message came from
     * @param dlqUrl   the dead letter queue
     * @return completion when the message is deleted, moved or left for a new delivery
     */
    public Mono<Void> handle(Throwable error, Message message, String queueUrl, String dlqUrl) {
        return switch (error) {
            case BusinessException business when business.getBusinessErrorMessage() == INVALID_STATE_TRANSITION -> {
                log.warn("Message dropped after a lost race, code={}, messageId={}",
                        INVALID_STATE_TRANSITION.getCode(), message.messageId());
                yield delete(message, queueUrl);
            }
            case BusinessException business -> {
                log.warn("Message moved to the dead letter queue, code={}, messageId={}",
                        business.getBusinessErrorMessage().getCode(), message.messageId());
                yield deadLetter(message, business.getBusinessErrorMessage().getCode(), queueUrl, dlqUrl);
            }
            case TechnicalException technical when !technical.getErrorMessage().getRetryable() -> {
                log.error("Message moved to the dead letter queue, code={}, messageId={}",
                        technical.getErrorMessage().getCode(), message.messageId());
                yield deadLetter(message, technical.getErrorMessage().getCode(), queueUrl, dlqUrl);
            }
            default -> {
                log.error("Message left for a new delivery, messageId={}", message.messageId(), error);
                yield Mono.empty();
            }
        };
    }

    /**
     * Deletes a message whose processing completed.
     *
     * @param message  the message
     * @param queueUrl the queue the message came from
     * @return completion when the message is deleted
     */
    public Mono<Void> delete(Message message, String queueUrl) {
        return Mono.fromFuture(() -> {
                    log.debug("SQS DeleteMessage, queue={}, messageId={}", queueUrl, message.messageId());
                    return client.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                })
                .then()
                .onErrorMap(error -> SQS_ERROR.build(error));
    }

    private Mono<Void> deadLetter(Message message, String code, String queueUrl, String dlqUrl) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>(message.messageAttributes());
        attributes.put(FAILURE_CODE_ATTRIBUTE, MessageAttributeValue.builder().dataType(STRING_TYPE)
                .stringValue(code).build());
        return Mono.fromFuture(() -> {
                    log.debug("SQS SendMessage, queue={}, messageId={}, failureCode={}", dlqUrl, message.messageId(),
                            code);
                    return client.sendMessage(SendMessageRequest.builder()
                            .queueUrl(dlqUrl)
                            .messageBody(message.body())
                            .messageAttributes(attributes)
                            .build());
                })
                .onErrorMap(error -> SQS_ERROR.build(error))
                .then(Mono.defer(() -> delete(message, queueUrl)))
                .doOnSuccess(done -> meterRegistry.counter(DEAD_LETTERED_METRIC, REASON_TAG, code).increment());
    }
}
