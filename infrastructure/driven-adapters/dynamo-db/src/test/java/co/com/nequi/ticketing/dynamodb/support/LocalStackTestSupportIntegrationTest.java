package co.com.nequi.ticketing.dynamodb.support;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStackTestSupportIntegrationTest {

    @Test
    void shouldCreateAllTablesWhenContainerStarts() {
        // Given
        DynamoDbAsyncClient client = LocalStackTestSupport.asyncClient();

        // When / Then
        StepVerifier.create(Mono.fromFuture(client::listTables))
                .assertNext(response -> assertThat(response.tableNames())
                        .contains(LocalStackTestSupport.EVENTS_TABLE, LocalStackTestSupport.INVENTORY_TABLE,
                                LocalStackTestSupport.ORDERS_TABLE, LocalStackTestSupport.ORDER_AUDIT_TABLE,
                                LocalStackTestSupport.IDEMPOTENCY_TABLE, LocalStackTestSupport.CUSTOMER_LIMITS_TABLE))
                .verifyComplete();
    }

    @Test
    void shouldCreateQueueAndDeadLetterQueueWhenContainerStarts() {
        // When / Then
        StepVerifier.create(Mono.fromFuture(LocalStackTestSupport.sqsAsyncClient()::listQueues))
                .assertNext(response -> assertThat(response.queueUrls())
                        .anyMatch(url -> url.endsWith("/" + LocalStackTestSupport.ORDER_PLACED_QUEUE))
                        .anyMatch(url -> url.endsWith("/" + LocalStackTestSupport.ORDER_PLACED_DLQ)))
                .verifyComplete();
    }
}
