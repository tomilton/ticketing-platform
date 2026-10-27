package co.com.nequi.ticketing.order;

import co.com.nequi.ticketing.dynamodb.support.LocalStackTestSupport;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.Quantity;
import co.com.nequi.ticketing.model.order.event.OrderPlacedMessage;
import co.com.nequi.ticketing.model.shared.EventId;
import co.com.nequi.ticketing.sqs.commons.OrderPlacedMessageCodec;
import co.com.nequi.ticketing.sqs.sender.SqsOrderEventPublisher;
import co.com.nequi.ticketing.sqs.sender.config.SqsSenderProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publisher writes the version 1 contract to the real queue of LocalStack.
 */
class SqsOrderEventPublisherIntegrationTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void shouldPublishMessageThatCanBeReadFromQueueWhenOrderIsAccepted() {
        // Given
        SqsAsyncClient sqs = LocalStackTestSupport.sqsAsyncClient();
        OrderPlacedMessageCodec codec = new OrderPlacedMessageCodec(JsonMapper.builder().build());
        SqsOrderEventPublisher publisher = new SqsOrderEventPublisher(sqs, new SqsSenderProperties("us-east-1", null,
                LocalStackTestSupport.ORDER_PLACED_QUEUE, Duration.ofSeconds(5)), codec, ObservationRegistry.NOOP);
        OrderPlacedMessage message = new OrderPlacedMessage(UUID.randomUUID(), OrderId.newId(), EventId.newId(),
                new Quantity(2), Instant.parse("2026-10-27T10:00:00Z"));

        // When
        Mono<OrderPlacedMessage> received = publisher.publishOrderPlaced(message)
                .then(Mono.fromFuture(() -> sqs.getQueueUrl(GetQueueUrlRequest.builder()
                        .queueName(LocalStackTestSupport.ORDER_PLACED_QUEUE).build())))
                .flatMap(url -> Flux.range(0, 10)
                        .concatMap(attempt -> Mono.fromFuture(() -> sqs.receiveMessage(ReceiveMessageRequest.builder()
                                        .queueUrl(url.queueUrl()).maxNumberOfMessages(10).waitTimeSeconds(1)
                                        .messageAttributeNames("All").build()))
                                .flatMapIterable(ReceiveMessageResponse::messages))
                        .filter(candidate -> candidate.body().contains(message.orderId().value().toString()))
                        .map(candidate -> {
                            assertThat(candidate.messageAttributes().get("correlationId").stringValue())
                                    .isEqualTo(message.orderId().value().toString());
                            return codec.decode(candidate.body());
                        })
                        .next());

        // Then
        StepVerifier.create(received)
                .expectNext(message)
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }
}
