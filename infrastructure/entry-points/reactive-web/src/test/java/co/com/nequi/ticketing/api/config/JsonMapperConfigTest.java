package co.com.nequi.ticketing.api.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMapperConfigTest {

    private final JsonMapper mapper = configuredMapper();

    @Test
    void shouldRejectDecimalWhenFieldIsInteger() {
        // When / Then
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":2.5}", QuantityDto.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void shouldReadIntegerWhenFieldIsInteger() {
        // When / Then
        assertThat(mapper.readValue("{\"quantity\":2}", QuantityDto.class)).isEqualTo(new QuantityDto(2));
    }

    private static JsonMapper configuredMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JsonMapperConfig().integerFieldsWithoutDecimals().customize(builder);
        return builder.build();
    }

    record QuantityDto(Integer quantity) {
    }
}
