package co.com.nequi.ticketing.config;

import co.com.nequi.ticketing.dynamodb.config.DynamoDbProperties;
import co.com.nequi.ticketing.model.config.IdempotencySettings;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutConsistencyConfigTest {

    @ParameterizedTest
    @CsvSource({"PT5S, true", "PT10S, false", "PT11S, false"})
    void shouldStartOnlyWhenReserveTimeoutIsShorterThanLease(Duration reserveTimeout, Boolean starts) {
        // Given
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(TimeoutConsistencyConfig.class)
                .withBean(IdempotencySettings.class, () -> new IdempotencySettings(Duration.ofHours(24),
                        Duration.ofSeconds(10)))
                .withBean(DynamoDbProperties.class, () -> new DynamoDbProperties("us-east-1", null, "events",
                        "inventory", "by-status-date", Duration.ofSeconds(2), Duration.ofSeconds(1), 4,
                        Duration.ofSeconds(1), "orders", "order_audit", "idempotency", "customer_event_limits",
                        "expiring-reservations", "pending-publication", 10, reserveTimeout));

        // When / Then
        runner.run(context -> assertThat(context.getStartupFailure() == null).isEqualTo(starts));
    }
}
