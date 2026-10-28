package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.model.config.AvailabilityWatchSettings;
import co.com.nequi.ticketing.model.config.ComplimentarySettings;
import co.com.nequi.ticketing.model.config.IdempotencySettings;
import co.com.nequi.ticketing.model.config.InventorySettings;
import co.com.nequi.ticketing.model.config.OrderSettings;
import co.com.nequi.ticketing.model.config.PaymentNotificationSettings;
import co.com.nequi.ticketing.model.config.SweepSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.UUID;

/**
 * Business parameters of the domain, read from the configuration and validated by the domain
 * records when the application starts.
 */
@Configuration
@EnableConfigurationProperties({BusinessSettingsConfig.InventoryProperties.class,
        BusinessSettingsConfig.AvailabilityProperties.class, BusinessSettingsConfig.OrderProperties.class,
        BusinessSettingsConfig.IdempotencyProperties.class, BusinessSettingsConfig.SweepProperties.class,
        BusinessSettingsConfig.ComplimentaryProperties.class, BusinessSettingsConfig.PaymentNotificationProperties.class})
public class BusinessSettingsConfig {

    @Bean
    public InventorySettings inventorySettings(InventoryProperties properties) {
        return new InventorySettings(properties.shardCount());
    }

    @Bean
    public AvailabilityWatchSettings availabilityWatchSettings(AvailabilityProperties properties) {
        return new AvailabilityWatchSettings(properties.pollInterval());
    }

    @Bean
    public OrderSettings orderSettings(OrderProperties properties) {
        return new OrderSettings(properties.maxTicketsPerOrder(), properties.maxTicketsPerCustomer(),
                properties.reservationTtl(), properties.republishGrace(), properties.republishConcurrency());
    }

    /**
     * Identity of this replica, generated when it starts; it owns the locks of the periodic tasks.
     *
     * @return a new random identifier
     */
    @Bean
    public UUID instanceId() {
        return UUID.randomUUID();
    }

    @Bean
    public IdempotencySettings idempotencySettings(IdempotencyProperties properties) {
        return new IdempotencySettings(properties.ttl(), properties.lease());
    }

    @Bean
    public SweepSettings sweepSettings(SweepProperties properties) {
        return new SweepSettings(properties.lockLease(), properties.batchSize(), properties.concurrency());
    }

    @Bean
    public ComplimentarySettings complimentarySettings(ComplimentaryProperties properties) {
        return new ComplimentarySettings(properties.maxPerGrant());
    }

    @Bean
    public PaymentNotificationSettings paymentNotificationSettings(PaymentNotificationProperties properties) {
        return new PaymentNotificationSettings(properties.eventTtl());
    }

    /**
     * The {@code inventory} block.
     *
     * @param shardCount the number of shards of a new event
     */
    @ConfigurationProperties("inventory")
    public record InventoryProperties(Integer shardCount) {
    }

    /**
     * The business part of the {@code availability} block.
     *
     * @param pollInterval the time between two reads of the live availability
     */
    @ConfigurationProperties("availability")
    public record AvailabilityProperties(Duration pollInterval) {
    }

    /**
     * The business part of the {@code orders} block.
     *
     * @param maxTicketsPerOrder    the tickets of one order
     * @param maxTicketsPerCustomer the tickets a customer can hold for one event
     * @param reservationTtl        how long a reservation holds its tickets
     * @param republishGrace        how old an unpublished order must be to publish it again
     * @param republishConcurrency  the orders published again at the same time
     */
    @ConfigurationProperties("orders")
    public record OrderProperties(Integer maxTicketsPerOrder, Integer maxTicketsPerCustomer, Duration reservationTtl,
                                  Duration republishGrace, Integer republishConcurrency) {
    }

    /**
     * The {@code idempotency} block.
     *
     * @param ttl   how long a key is kept
     * @param lease how long a request holds a key
     */
    @ConfigurationProperties("idempotency")
    public record IdempotencyProperties(Duration ttl, Duration lease) {
    }

    /**
     * The business part of the {@code sweep} block; its interval is read by the scheduler.
     *
     * @param lockLease   how long a replica holds the sweep
     * @param batchSize   the expired orders released in one cycle
     * @param concurrency the orders released at the same time
     */
    @ConfigurationProperties("sweep")
    public record SweepProperties(Duration lockLease, Integer batchSize, Integer concurrency) {
    }

    /**
     * The {@code complimentary} block.
     *
     * @param maxPerGrant the tickets of one grant
     */
    @ConfigurationProperties("complimentary")
    public record ComplimentaryProperties(Integer maxPerGrant) {
    }

    /**
     * The business part of the {@code webhook} block.
     *
     * @param eventTtl how long a received notice of the payment gateway is remembered
     */
    @ConfigurationProperties("webhook")
    public record PaymentNotificationProperties(Duration eventTtl) {
    }
}
