package co.com.nequi.ticketing.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * Reading rules of the JSON bodies. A decimal number is never truncated into an integer field, so
 * a quantity such as {@code 2.5} is rejected instead of read as {@code 2}.
 */
@Configuration
public class JsonMapperConfig {

    @Bean
    public JsonMapperBuilderCustomizer integerFieldsWithoutDecimals() {
        return builder -> builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
