package co.com.nequi.ticketing.sqs.sender.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

import java.net.URI;
import java.util.Optional;

/**
 * SQS client of the publisher. The SDK does not retry and each call is bounded by the send timeout;
 * a failed publication is covered by the republication of pending orders. Credentials come from the
 * default provider chain.
 */
@Configuration
public class SqsSenderClientConfig {

    public static final String SENDER_CLIENT = "sqsSenderClient";

    @Bean(SENDER_CLIENT)
    public SqsAsyncClient sqsSenderClient(SqsSenderProperties properties, MetricPublisher metricPublisher) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(properties.region()))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .overrideConfiguration(override -> override
                        .apiCallTimeout(properties.sendTimeout())
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .addMetricPublisher(metricPublisher));
        Optional.ofNullable(properties.endpoint())
                .filter(endpoint -> !endpoint.isBlank())
                .map(URI::create)
                .ifPresent(builder::endpointOverride);
        return builder.build();
    }
}
