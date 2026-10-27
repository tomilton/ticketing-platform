package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.model.config.AvailabilityWatchSettings;
import co.com.nequi.ticketing.model.config.InventorySettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessSettingsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BusinessSettingsConfig.class)
            .withPropertyValues("availability.poll-interval=1s");

    @ParameterizedTest
    @ValueSource(strings = {"0", "21"})
    void shouldNotStartWhenShardCountIsOutOfRange(String shardCount) {
        // When / Then
        contextRunner.withPropertyValues("inventory.shard-count=" + shardCount)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldExposeBusinessSettingsWhenValuesAreValid() {
        // When / Then
        contextRunner.withPropertyValues("inventory.shard-count=1")
                .run(context -> {
                    assertThat(context.getBean(InventorySettings.class)).isEqualTo(new InventorySettings(1));
                    assertThat(context.getBean(AvailabilityWatchSettings.class))
                            .isEqualTo(new AvailabilityWatchSettings(Duration.ofSeconds(1)));
                });
    }
}
