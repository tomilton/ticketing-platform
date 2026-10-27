package co.com.nequi.ticketing.dynamodb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbPropertiesTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void shouldThrowIllegalArgumentExceptionWhenTimeoutIsNotPositive(String timeout) {
        // Given
        Duration invalid = Duration.parse(timeout);

        // When / Then
        assertThatThrownBy(() -> properties(invalid, ONE_SECOND, 1, ONE_SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(ONE_SECOND, invalid, 1, ONE_SECOND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(ONE_SECOND, ONE_SECOND, 1, invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenBatchConcurrencyIsLowerThanOne() {
        // When / Then
        assertThatThrownBy(() -> properties(ONE_SECOND, ONE_SECOND, 0, ONE_SECOND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenRequiredValueIsNull() {
        // When / Then
        assertThatThrownBy(() -> properties(null, ONE_SECOND, 1, ONE_SECOND))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldKeepValuesWhenAllValuesAreValid() {
        // When
        DynamoDbProperties properties = properties(ONE_SECOND, ONE_SECOND, 1, ONE_SECOND);

        // Then
        assertThat(properties.batchConcurrency()).isEqualTo(1);
        assertThat(properties.endpoint()).isNull();
    }

    private static DynamoDbProperties properties(Duration apiCallTimeout, Duration apiCallAttemptTimeout,
                                                 Integer batchConcurrency, Duration healthCheckTimeout) {
        return new DynamoDbProperties("us-east-1", null, "events", "inventory", "by-status-date",
                apiCallTimeout, apiCallAttemptTimeout, batchConcurrency, healthCheckTimeout);
    }
}
