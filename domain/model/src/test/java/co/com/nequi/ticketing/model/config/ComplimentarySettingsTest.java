package co.com.nequi.ticketing.model.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplimentarySettingsTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void shouldKeepValueWhenMaxPerGrantIsWithinRange(Integer maxPerGrant) {
        // When / Then
        assertThat(new ComplimentarySettings(maxPerGrant).maxPerGrant()).isEqualTo(maxPerGrant);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void shouldThrowIllegalArgumentExceptionWhenMaxPerGrantIsOutOfRange(Integer maxPerGrant) {
        // When / Then
        assertThatThrownBy(() -> new ComplimentarySettings(maxPerGrant)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenMaxPerGrantIsNull() {
        // When / Then
        assertThatThrownBy(() -> new ComplimentarySettings(null)).isInstanceOf(NullPointerException.class);
    }
}
