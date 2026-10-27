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
import co.com.nequi.ticketing.model.enums.OrderStatus;
import co.com.nequi.ticketing.model.enums.StatusChangeReason;
import co.com.nequi.ticketing.model.exception.BusinessException;
import co.com.nequi.ticketing.model.inventory.InventoryMovement;
import co.com.nequi.ticketing.model.order.Order;
import co.com.nequi.ticketing.model.order.OrderId;
import co.com.nequi.ticketing.model.order.OrderTransition;
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
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDynamoRepositoryTransitionTest {

    @Mock
    private DynamoDbAsyncClient client;

    private OrderDynamoRepository repository;

    private OrderTransition transition;

    @BeforeEach
    void setUp() {
        DynamoDbProperties properties = OrderFixtures.properties(2, Duration.ofSeconds(5));
        OrderEntityMapper orderMapper = Mappers.getMapper(OrderEntityMapper.class);
        AuditItemBuilder auditItemBuilder = new AuditItemBuilder(properties);
        repository = new OrderDynamoRepository(client, properties, orderMapper,
                new ReservationTransactionBuilder(properties, orderMapper, new IdempotencyEntityMapper(), auditItemBuilder),
                new TransitionTransactionBuilder(properties, auditItemBuilder), new TransactionFailureTranslator(),
                new ComplimentaryTransactionBuilder(properties, orderMapper, auditItemBuilder));
        Order reserved = OrderFixtures.order(OrderFixtures.event(1), 2, 0);
        transition = reserved.transitionTo(OrderStatus.PENDING_CONFIRMATION, StatusChangeReason.PAYMENT_REQUESTED,
                NOW.plusSeconds(1));
    }

    @Test
    void shouldReturnNextOrderWhenTransitionIsWritten() {
        // Given
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.apply(transition, InventoryMovement.NONE))
                .expectNext(transition.next())
                .verifyComplete();
    }

    @Test
    void shouldFailWithInvalidTransitionWhenOrderConditionFails() {
        // Given
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancellation("ConditionalCheckFailed", "None")));

        // When / Then
        StepVerifier.create(repository.apply(transition, InventoryMovement.NONE))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(exception -> ((BusinessException) exception).getBusinessErrorMessage())
                        .isEqualTo(BusinessErrorMessage.INVALID_STATE_TRANSITION))
                .verify();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenDefensiveConditionFails() {
        // Given
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancellation("None", "ConditionalCheckFailed", "None")));

        // When / Then
        StepVerifier.create(repository.apply(transition, InventoryMovement.RELEASE))
                .expectErrorSatisfies(OrderDynamoRepositoryTransitionTest::assertDynamoDbError)
                .verify();
    }

    @Test
    void shouldRetryTransitionWhenTransactionConflicts() {
        // Given
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancellation("TransactionConflict", "None")))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        // When / Then
        StepVerifier.withVirtualTime(() -> repository.apply(transition, InventoryMovement.NONE))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(transition.next())
                .verifyComplete();
    }

    @Test
    void shouldRemovePublishBucketWithoutTouchingVersionWhenOrderIsMarkedAsPublished() {
        // Given
        OrderId orderId = transition.previous().getOrderId();
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        when(client.updateItem(captor.capture()))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository.markPublished(orderId, NOW))
                .verifyComplete();
        UpdateItemRequest request = captor.getValue();
        assertThat(request.tableName()).isEqualTo("orders");
        assertThat(request.updateExpression()).isEqualTo("REMOVE publishBucket SET publishedAt = :nowMillis");
        assertThat(request.conditionExpression()).isEqualTo("attribute_exists(orderId)");
        assertThat(request.expressionAttributeValues())
                .isEqualTo(Map.of(":nowMillis", AttributeValue.fromN(String.valueOf(NOW.toEpochMilli()))));
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenMarkFails() {
        // Given
        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database down")));

        // When / Then
        StepVerifier.create(repository.markPublished(transition.previous().getOrderId(), NOW))
                .expectErrorSatisfies(OrderDynamoRepositoryTransitionTest::assertDynamoDbError)
                .verify();
    }

    @Test
    void shouldQueryTenBucketsOfPendingIndexWhenPendingOrdersAreSearched() {
        // Given
        Instant before = NOW.minusSeconds(5);
        OrderId pending = transition.previous().getOrderId();
        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        when(client.queryPaginator(captor.capture()))
                .thenAnswer(invocation -> new QueryPublisher(client, invocation.getArgument(0)));
        when(client.query(any(QueryRequest.class))).thenAnswer(invocation -> CompletableFuture.completedFuture(
                bucketOf(invocation.getArgument(0)).equals("PENDING#3")
                        ? QueryResponse.builder().items(List.of(Map.of("orderId",
                        AttributeValue.fromS(pending.value().toString())))).build()
                        : QueryResponse.builder().items(List.of()).build()));

        // When / Then
        StepVerifier.create(repository.findPendingPublication(before))
                .expectNext(pending)
                .verifyComplete();
        assertThat(captor.getAllValues()).hasSize(10).allSatisfy(request -> {
            assertThat(request.indexName()).isEqualTo("pending-publication");
            assertThat(request.keyConditionExpression()).isEqualTo("publishBucket = :bucket AND createdAt < :before");
            assertThat(request.expressionAttributeValues().get(":before"))
                    .isEqualTo(AttributeValue.fromN(String.valueOf(before.toEpochMilli())));
        });
    }

    private static String bucketOf(QueryRequest request) {
        return request.expressionAttributeValues().get(":bucket").s();
    }

    private static TransactionCanceledException cancellation(String... codes) {
        return TransactionCanceledException.builder()
                .cancellationReasons(Arrays.stream(codes)
                        .map(code -> CancellationReason.builder().code(code).build())
                        .toList())
                .build();
    }

    private static void assertDynamoDbError(Throwable error) {
        assertThat(error).isInstanceOf(TechnicalException.class)
                .extracting(exception -> ((TechnicalException) exception).getErrorMessage())
                .isEqualTo(TechnicalErrorMessage.DYNAMODB_ERROR);
    }
}
