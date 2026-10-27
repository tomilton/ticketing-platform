package co.com.nequi.ticketing.model.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventorySettingsTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void shouldThrowIllegalArgumentExceptionWhenShardCountIsOutOfRange(Integer shardCount) {
        // When / Then
        assertThatThrownBy(() -> new InventorySettings(shardCount)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void shouldKeepShardCountWhenShardCountIsWithinRange(Integer shardCount) {
        // When / Then
        assertThat(new InventorySettings(shardCount).shardCount()).isEqualTo(shardCount);
    }
}
