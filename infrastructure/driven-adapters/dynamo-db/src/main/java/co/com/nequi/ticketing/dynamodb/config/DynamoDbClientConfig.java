package co.com.nequi.ticketing.dynamodb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;

import java.net.URI;
import java.util.Optional;

/**
 * DynamoDB clients. The SDK does not retry: the adapter retries in Reactor with its own filter.
 * Credentials come from the default provider chain.
 */
@Configuration
public class DynamoDbClientConfig {

    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient(DynamoDbProperties properties, MetricPublisher metricPublisher) {
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .region(Region.of(properties.region()))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(properties.apiCallTimeout())
                        .apiCallAttemptTimeout(properties.apiCallAttemptTimeout())
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .addMetricPublisher(metricPublisher));
        Optional.ofNullable(properties.endpoint())
                .filter(endpoint -> !endpoint.isBlank())
                .map(URI::create)
                .ifPresent(builder::endpointOverride);
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbAsyncClient)
                .build();
    }
}
