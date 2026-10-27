package co.com.nequi.ticketing.dynamodb.health;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamoDbHealthIndicatorTest {

    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(1);

    @Mock
    private DynamoDbAsyncClient client;

    private DynamoDbHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        DynamoDbProperties properties = new DynamoDbProperties("us-east-1", null, "events-table",
                "inventory-table", "by-status-date", Duration.ofSeconds(2), Duration.ofMillis(500), 4,
                HEALTH_CHECK_TIMEOUT,
                "orders", "order_audit", "idempotency", "customer_event_limits", "expiring-reservations",
                "pending-publication", 10, Duration.ofSeconds(5));
        indicator = new DynamoDbHealthIndicator(client, properties);
    }

    @Test
    void shouldBeUpWithEachTableStatusWhenBothTablesAreActive() {
        // Given
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(describeResponse(TableStatus.ACTIVE)));

        // When / Then
        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).isEqualTo(Map.of("events", "ACTIVE", "inventory", "ACTIVE"));
                })
                .verifyComplete();
    }

    @Test
    void shouldBeDownWhenTableIsNotActive() {
        // Given
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(describeResponse(TableStatus.ACTIVE)),
                        CompletableFuture.completedFuture(describeResponse(TableStatus.CREATING)));

        // When / Then
        StepVerifier.create(indicator.health())
                .assertNext(health -> assertThat(health.getStatus()).isEqualTo(Status.DOWN))
                .verifyComplete();
    }

    @Test
    void shouldBeDownWithOnlyErrorCodeWhenTableDoesNotExist() {
        // Given
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(ResourceNotFoundException.builder()
                        .message("Requested resource not found: Table: events-table not found").build()));

        // When / Then
        StepVerifier.create(indicator.health())
                .assertNext(DynamoDbHealthIndicatorTest::assertDownWithCodeOnly)
                .verifyComplete();
    }

    @Test
    void shouldBeDownWithOnlyErrorCodeWhenSdkFails() {
        // Given
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkException.create("connection refused", null)));

        // When / Then
        StepVerifier.create(indicator.health())
                .assertNext(DynamoDbHealthIndicatorTest::assertDownWithCodeOnly)
                .verifyComplete();
    }

    @Test
    void shouldBeDownWhenResponseIsSlowerThanHealthCheckTimeout() {
        // Given
        when(client.describeTable(any(DescribeTableRequest.class))).thenReturn(new CompletableFuture<>());

        // When / Then
        StepVerifier.withVirtualTime(() -> indicator.health())
                .thenAwait(HEALTH_CHECK_TIMEOUT)
                .assertNext(DynamoDbHealthIndicatorTest::assertDownWithCodeOnly)
                .verifyComplete();
    }

    private static void assertDownWithCodeOnly(Health health) {
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEqualTo(Map.of("code", "TKT0005"));
    }

    private static DescribeTableResponse describeResponse(TableStatus status) {
        return DescribeTableResponse.builder()
                .table(TableDescription.builder().tableStatus(status).build())
                .build();
    }
}
