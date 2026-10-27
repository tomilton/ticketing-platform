package co.com.nequi.ticketing.dynamodb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DynamoDbClientConfigTest {

    private static final String ENDPOINT = "http://localhost:4566";
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofMillis(500);

    @Mock
    private MetricPublisher metricPublisher;

    private final DynamoDbClientConfig config = new DynamoDbClientConfig();

    @Test
    void shouldNotOverrideEndpointWhenEndpointIsMissing() {
        // When
        try (DynamoDbAsyncClient client = config.dynamoDbAsyncClient(properties(null), metricPublisher)) {
            // Then
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void shouldOverrideEndpointWhenEndpointIsPresent() {
        // When
        try (DynamoDbAsyncClient client = config.dynamoDbAsyncClient(properties(ENDPOINT), metricPublisher)) {
            // Then
            assertThat(client.serviceClientConfiguration().endpointOverride()).contains(URI.create(ENDPOINT));
        }
    }

    @Test
    void shouldApplyTimeoutsSingleAttemptAndMetricPublisherWhenClientIsBuilt() {
        // When
        try (DynamoDbAsyncClient client = config.dynamoDbAsyncClient(properties(null), metricPublisher)) {
            ClientOverrideConfiguration override = client.serviceClientConfiguration().overrideConfiguration();

            // Then
            assertThat(override.apiCallTimeout()).contains(API_CALL_TIMEOUT);
            assertThat(override.apiCallAttemptTimeout()).contains(API_CALL_ATTEMPT_TIMEOUT);
            assertThat(override.retryStrategy()).hasValueSatisfying(strategy ->
                    assertThat(strategy.maxAttempts()).isEqualTo(1));
            assertThat(override.metricPublishers()).containsExactly(metricPublisher);
        }
    }

    @Test
    void shouldWrapAsyncClientWhenEnhancedClientIsBuilt() {
        // Given
        try (DynamoDbAsyncClient client = config.dynamoDbAsyncClient(properties(null), metricPublisher)) {
            // When
            DynamoDbEnhancedAsyncClient enhancedClient = config.dynamoDbEnhancedAsyncClient(client);

            // Then
            assertThat(enhancedClient).isInstanceOf(DynamoDbEnhancedAsyncClient.class);
        }
    }

    private static DynamoDbProperties properties(String endpoint) {
        return new DynamoDbProperties("us-east-1", endpoint, "events", "inventory", "by-status-date",
                API_CALL_TIMEOUT, API_CALL_ATTEMPT_TIMEOUT, 4, Duration.ofSeconds(1),
                "orders", "order_audit", "idempotency", "customer_event_limits", "expiring-reservations",
                "pending-publication", 10, Duration.ofSeconds(5));
    }
}
