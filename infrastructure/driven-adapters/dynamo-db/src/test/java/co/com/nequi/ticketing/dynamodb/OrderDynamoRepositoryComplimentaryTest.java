package co.com.nequi.ticketing.dynamodb;

import co.com.nequi.ticketing.commons.exception.TechnicalErrorMessage;
import co.com.nequi.ticketing.commons.exception.TechnicalException;
import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.dynamodb.mapper.IdempotencyEntityMapper;
import co.com.nequi.ticketing.dynamodb.mapper.OrderEntityMapper;
import co.com.nequi.ticketing.dynamodb.support.OrderFixtures;
import co.com.nequi.ticketing.dynamodb.transaction.AuditItemBuilder;
import co.com.nequi.ticketing.dynamodb.transaction.ComplimentaryTransactionBuilder;
import co.com.nequi.ticketing.dynamodb.transaction.ReservationTransactionBuilder;
import co.com.nequi.ticketing.dynamodb.transaction.TransactionFailureTranslator;
import co.com.nequi.ticketing.dynamodb.transaction.TransitionTransactionBuilder;
import co.com.nequi.ticketing.model.enums.BusinessErrorMessage;
import co.com.nequi.ticketing.model.event.Event;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.order.ComplimentaryQuantity;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.paginators.QueryPublisher;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.CUSTOMER_ID;
import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDynamoRepositoryComplimentaryTest {

    private static final String FAILED = "ConditionalCheckFailed";

    @Mock
    private DynamoDbAsyncClient client;

    private OrderDynamoRepository repository;

    @BeforeEach
    void setUp() {
        DynamoDbProperties properties = OrderFixtures.properties(2, Duration.ofSeconds(5));
        OrderEntityMapper orderMapper = Mappers.getMapper(OrderEntityMapper.class);
        AuditItemBuilder auditItemBuilder = new AuditItemBuilder(properties);
        repository = new OrderDynamoRepository(client, properties, orderMapper,
                new ReservationTransactionBuilder(properties, orderMapper, new IdempotencyEntityMapper(), auditItemBuilder),
                new TransitionTransactionBuilder(properties, auditItemBuilder), new TransactionFailureTranslator(),
                new ComplimentaryTransactionBuilder(properties, orderMapper, auditItemBuilder));
    }

    @Test
    void shouldWriteEventShardOrderAndAuditWhenComplimentaryIsGranted() {
        // Given
        Event event = OrderFixtures.event(1);
        Order order = complimentary(event, 3, 0);
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        when(client.transactWriteItems(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.grantComplimentary(order, event, NOW))
                .assertNext(granted -> assertThat(granted).usingRecursiveComparison().isEqualTo(order))
                .verifyComplete();
        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items).hasSize(4);
        assertThat(items.get(0).conditionCheck().conditionExpression())
                .isEqualTo("#status = :active AND #date > :nowMillis");
        assertThat(items.get(1).update().updateExpression())
                .isEqualTo("SET available = available - :q, complimentary = complimentary + :q, version = version + :one");
        assertThat(items.get(1).update().conditionExpression()).isEqualTo("available >= :q");
        assertThat(items.get(1).update().expressionAttributeValues().get(":q")).isEqualTo(AttributeValue.fromN("3"));
        Map<String, AttributeValue> stored = items.get(2).put().item();
        assertThat(stored.get("status").s()).isEqualTo("COMPLIMENTARY");
        assertThat(stored).doesNotContainKeys("activeBucket", "publishBucket");
        assertThat(items.get(2).put().conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
        assertThat(items.get(3).put().item().get("actor").s()).isEqualTo("ADMIN");
        assertThat(items.get(3).put().item().get("reason").s()).isEqualTo("COMPLIMENTARY_GRANTED");
        assertThat(items.get(3).put().item().get("toStatus").s()).isEqualTo("COMPLIMENTARY");
    }

    @Test
    void shouldTryOtherShardsAndFailWithSoldOutWhenNoShardHasTickets() {
        // Given
        Event event = OrderFixtures.event(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(cancellation("None", FAILED, "None", "None")));

        // When / Then
        StepVerifier.create(repository.grantComplimentary(complimentary(event, 3, 1), event, NOW))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKETS_SOLD_OUT))
                .verify();
        verify(client, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void shouldFailWithEventNotActiveWhenEventConditionFails() {
        // Given
        Event event = OrderFixtures.event(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancellation(FAILED, "None", "None", "None")));

        // When / Then
        StepVerifier.create(repository.grantComplimentary(complimentary(event, 3, 0), event, NOW))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.EVENT_NOT_ACTIVE))
                .verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenOrderConditionFails() {
        // Given
        Event event = OrderFixtures.event(1);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancellation("None", "None", FAILED, "None")));

        // When / Then
        StepVerifier.create(repository.grantComplimentary(complimentary(event, 3, 0), event, NOW))
                .expectErrorSatisfies(OrderDynamoRepositoryComplimentaryTest::assertDynamoDbError)
                .verify();
    }

    @Test
    void shouldQueryTenActiveBucketsUpToLimitWhenExpiredOrdersAreSearched() {
        // Given
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        when(client.queryPaginator(captor.capture()))
                .thenAnswer(invocation -> new QueryPublisher(client, invocation.getArgument(0)));
        when(client.query(any(QueryRequest.class))).thenAnswer(invocation -> CompletableFuture.completedFuture(
                QueryResponse.builder().items(IntStream.range(0, 2)
                        .mapToObj(index -> Map.of("orderId", AttributeValue.fromS(OrderId.newId().value().toString())))
                        .toList()).build()));

        // When / Then
        StepVerifier.create(repository.findExpired(NOW, 5))
                .expectNextCount(5)
                .verifyComplete();
        assertThat(captor.getAllValues()).isNotEmpty().allSatisfy(request -> {
            assertThat(request.indexName()).isEqualTo("expiring-reservations");
            assertThat(request.keyConditionExpression()).isEqualTo("activeBucket = :bucket AND expiresAt <= :nowMillis");
            assertThat(request.expressionAttributeValues().get(":nowMillis"))
                    .isEqualTo(AttributeValue.fromN(String.valueOf(NOW.toEpochMilli())));
            assertThat(request.expressionAttributeValues().get(":bucket").s()).startsWith("ACTIVE#");
        });
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenExpiredQueryFails() {
        // Given
        when(client.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(invocation -> new QueryPublisher(client, invocation.getArgument(0)));
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        // When / Then
        StepVerifier.create(repository.findExpired(NOW, 5))
                .expectErrorSatisfies(OrderDynamoRepositoryComplimentaryTest::assertDynamoDbError)
                .verify();
    }

    private static Order complimentary(Event event, Integer quantity, Integer shardId) {
        return Order.grantComplimentary(OrderId.newId(), CUSTOMER_ID, event.getEventId(),
                new ComplimentaryQuantity(quantity), shardId, NOW);
    }

    private static TransactionCanceledException cancellation(String... codes) {
        return TransactionCanceledException.builder()
                .cancellationReasons(Arrays.stream(codes)
                        .map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }

    private static void assertBusinessError(Throwable error, BusinessErrorMessage expected) {
        assertThat(error).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                .isEqualTo(expected);
    }

    private static void assertDynamoDbError(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }
}
