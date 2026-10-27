package co.com.nequi.ticketing.dynamodb.support;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStackTestSupportIntegrationTest {

    @Test
    void shouldCreateBothTablesWhenContainerStarts() {
        // Given
        DynamoDbAsyncClient client = LocalStackTestSupport.asyncClient();

        // When / Then
        StepVerifier.create(Mono.fromFuture(client::listTables))
                .assertNext(response -> assertThat(response.tableNames())
                        .contains(LocalStackTestSupport.EVENTS_TABLE, LocalStackTestSupport.INVENTORY_TABLE))
                .verifyComplete();
    }
}
