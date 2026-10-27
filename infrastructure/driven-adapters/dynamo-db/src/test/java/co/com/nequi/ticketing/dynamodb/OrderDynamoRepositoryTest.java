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
import co.com.nequi.ticketing.model.order.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static co.com.nequi.ticketing.dynamodb.support.OrderFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDynamoRepositoryTest {

    private static final String SHARD_FAILED = "ConditionalCheckFailed";

    @Mock
    private DynamoDbAsyncClient client;

    private final OrderEntityMapper orderMapper = Mappers.getMapper(OrderEntityMapper.class);

    @Test
    void shouldReturnOrderWithItsShardWhenSingleShardHasTickets() {
        // Given
        Event event = OrderFixtures.event(1);
        Order order = OrderFixtures.order(event, 2, 0);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository(10).reserve(order, event, OrderFixtures.context(), NOW))
                .assertNext(reserved -> assertThat(reserved).usingRecursiveComparison().isEqualTo(order))
                .verifyComplete();
    }

    @Test
    void shouldTryShardsInCircularOrderWhenFirstShardHasNoTickets() {
        // Given
        Event event = OrderFixtures.event(3);
        Order order = OrderFixtures.order(event, 1, 2);
        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        when(client.transactWriteItems(captor.capture()))
                .thenReturn(CompletableFuture.failedFuture(cancellation("None", SHARD_FAILED)))
                .thenReturn(CompletableFuture.failedFuture(cancellation("None", SHARD_FAILED)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository(10).reserve(order, event, OrderFixtures.context(), NOW))
                .assertNext(reserved -> assertThat(reserved.getShardId()).isEqualTo(1))
                .verifyComplete();
        assertThat(captor.getAllValues()).extracting(request -> request.transactItems()
                        .get(ReservationTransactionBuilder.SHARD_ITEM).update().key().get("shardId").n())
                .containsExactly("2", "0", "1");
    }

    @Test
    void shouldFailWithSoldOutWhenNoShardHasTickets() {
        // Given
        Event event = OrderFixtures.event(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(cancellation("None", SHARD_FAILED)));

        // When / Then
        StepVerifier.create(repository(10).reserve(OrderFixtures.order(event, 1, 0), event, OrderFixtures.context(), NOW))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKETS_SOLD_OUT))
                .verify();
        verify(client, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void shouldFailWithLimitExceededWithoutTryingOtherShardsWhenLimitConditionFails() {
        // Given
        Event event = OrderFixtures.event(3);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancellation("None", SHARD_FAILED, SHARD_FAILED)));

        // When / Then
        StepVerifier.create(repository(10).reserve(OrderFixtures.order(event, 1, 0), event, OrderFixtures.context(), NOW))
                .expectErrorSatisfies(error -> assertBusinessError(error, BusinessErrorMessage.TICKET_LIMIT_EXCEEDED))
                .verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void shouldRetryAndSucceedWhenTransactionConflictsTwice() {
        // Given
        Event event = OrderFixtures.event(1);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancellation("None", "TransactionConflict")))
                .thenReturn(CompletableFuture.failedFuture(cancellation("None", "TransactionConflict")))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        // When / Then
        StepVerifier.withVirtualTime(() -> repository(10)
                        .reserve(OrderFixtures.order(event, 1, 0), event, OrderFixtures.context(), NOW))
                .thenAwait(Duration.ofSeconds(2))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenConflictsExceedTheBudget() {
        // Given
        Event event = OrderFixtures.event(1);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(cancellation("None", "TransactionConflict")));

        // When / Then
        StepVerifier.withVirtualTime(() -> repository(2)
                        .reserve(OrderFixtures.order(event, 1, 0), event, OrderFixtures.context(), NOW))
                .thenAwait(Duration.ofSeconds(4))
                .expectErrorSatisfies(OrderDynamoRepositoryTest::assertDynamoDbError)
                .verify();
        verify(client, times(3)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void shouldFailWithDynamoDbErrorWhenReservationIsSlowerThanItsLimit() {
        // Given
        Event event = OrderFixtures.event(1);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(new CompletableFuture<>());

        // When / Then
        StepVerifier.withVirtualTime(() -> repository(10)
                        .reserve(OrderFixtures.order(event, 1, 0), event, OrderFixtures.context(), NOW))
                .thenAwait(Duration.ofSeconds(6))
                .expectErrorSatisfies(OrderDynamoRepositoryTest::assertDynamoDbError)
                .verify();
    }

    @Test
    void shouldReadWithStrongConsistencyWhenOrderIsFound() {
        // Given
        Event event = OrderFixtures.event(1);
        Order order = OrderFixtures.order(event, 2, 0);
        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        when(client.getItem(captor.capture())).thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder()
                .item(ReservationTransactionBuilder.ORDER_SCHEMA.itemToMap(orderMapper.toEntity(order), true))
                .build()));

        // When / Then
        StepVerifier.create(repository(10).findById(order.getOrderId()))
                .assertNext(found -> assertThat(found).usingRecursiveComparison().isEqualTo(order))
                .verifyComplete();
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().tableName()).isEqualTo("orders");
    }

    @Test
    void shouldReturnEmptyWhenOrderDoesNotExist() {
        // Given
        Order order = OrderFixtures.order(OrderFixtures.event(1), 2, 0);
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        // When / Then
        StepVerifier.create(repository(10).findById(order.getOrderId()))
                .verifyComplete();
    }

    private OrderDynamoRepository repository(Integer conflictRetries) {
        DynamoDbProperties properties = OrderFixtures.properties(conflictRetries, Duration.ofSeconds(5));
        AuditItemBuilder auditItemBuilder = new AuditItemBuilder(properties);
        ReservationTransactionBuilder builder = new ReservationTransactionBuilder(properties, orderMapper,
                new IdempotencyEntityMapper(), auditItemBuilder);
        return new OrderDynamoRepository(client, properties, orderMapper, builder,
                new TransitionTransactionBuilder(properties, auditItemBuilder), new TransactionFailureTranslator(),
                new ComplimentaryTransactionBuilder(properties, orderMapper, auditItemBuilder));
    }

    private static TransactionCanceledException cancellation(String... codes) {
        List<CancellationReason> reasons = Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList();
        return TransactionCanceledException.builder().cancellationReasons(reasons).build();
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
