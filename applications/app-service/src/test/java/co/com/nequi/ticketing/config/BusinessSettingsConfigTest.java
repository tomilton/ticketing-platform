package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.model.config.AvailabilityWatchSettings;
import co.com.nequi.ticketing.model.config.IdempotencySettings;
import co.com.nequi.ticketing.model.config.InventorySettings;
import co.com.nequi.ticketing.model.config.OrderSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessSettingsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BusinessSettingsConfig.class)
            .withPropertyValues("availability.poll-interval=1s", "orders.max-tickets-per-order=6",
                    "orders.max-tickets-per-customer=6", "orders.reservation-ttl=10m", "orders.republish-grace=5s",
                    "orders.republish-concurrency=4", "idempotency.ttl=24h", "idempotency.lease=10s");

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

    @Test
    void shouldExposeOrderAndIdempotencySettingsWhenValuesAreValid() {
        // When / Then
        contextRunner.withPropertyValues("inventory.shard-count=1")
                .run(context -> {
                    assertThat(context.getBean(OrderSettings.class)).isEqualTo(new OrderSettings(6, 6,
                            Duration.ofMinutes(10), Duration.ofSeconds(5), 4));
                    assertThat(context.getBean(IdempotencySettings.class))
                            .isEqualTo(new IdempotencySettings(Duration.ofHours(24), Duration.ofSeconds(10)));
                });
    }

    @Test
    void shouldExposeDistinctInstanceIdWhenEachContextStarts() {
        // When / Then
        contextRunner.withPropertyValues("inventory.shard-count=1")
                .run(first -> contextRunner.withPropertyValues("inventory.shard-count=1")
                        .run(second -> assertThat(first.getBean(UUID.class)).isNotEqualTo(second.getBean(UUID.class))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders.max-tickets-per-order=7", "orders.max-tickets-per-customer=5",
            "orders.republish-concurrency=0", "idempotency.lease=24h"})
    void shouldNotStartWhenOrderOrIdempotencySettingIsInvalid(String invalid) {
        // When / Then
        contextRunner.withPropertyValues("inventory.shard-count=1", invalid)
                .run(context -> assertThat(context).hasFailed());
    }
}
