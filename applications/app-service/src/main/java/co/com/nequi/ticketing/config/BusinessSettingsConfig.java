package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.model.config.AvailabilityWatchSettings;
import co.com.nequi.ticketing.model.config.InventorySettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Business parameters of the domain, read from the configuration and validated by the domain
 * records when the application starts.
 */
@Configuration
@EnableConfigurationProperties({BusinessSettingsConfig.InventoryProperties.class,
        BusinessSettingsConfig.AvailabilityProperties.class})
public class BusinessSettingsConfig {

    @Bean
    public InventorySettings inventorySettings(InventoryProperties properties) {
        return new InventorySettings(properties.shardCount());
    }

    @Bean
    public AvailabilityWatchSettings availabilityWatchSettings(AvailabilityProperties properties) {
        return new AvailabilityWatchSettings(properties.pollInterval());
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
}
