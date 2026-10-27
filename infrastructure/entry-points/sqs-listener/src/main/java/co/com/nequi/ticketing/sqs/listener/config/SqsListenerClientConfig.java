package co.com.nequi.ticketing.sqs.listener.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * SQS client of the listener, only in the {@code worker} role. The SDK does not retry: the listener
 * retries the receive itself and SQS delivers a message again when it is not deleted. A call may
 * last the long polling time plus a margin.
 */
@Configuration
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class SqsListenerClientConfig {

    public static final String LISTENER_CLIENT = "sqsListenerClient";
    private static final Duration CALL_MARGIN = Duration.ofSeconds(10);

    @Bean(LISTENER_CLIENT)
    public SqsAsyncClient sqsListenerClient(SqsListenerProperties properties, MetricPublisher metricPublisher) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(properties.region()))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(properties.waitTime().plus(CALL_MARGIN))
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .addMetricPublisher(metricPublisher));
        Optional.ofNullable(properties.endpoint())
                .filter(endpoint -> !endpoint.isBlank())
                .map(URI::create)
                .ifPresent(builder::endpointOverride);
        return builder.build();
    }
}
