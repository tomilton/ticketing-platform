package co.com.nequi.ticketing.api.config;

import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void shouldDeclareBearerSchemeWhenDocumentIsBuilt() {
        // When
        SecurityScheme scheme = new OpenApiConfig().openApi().getComponents().getSecuritySchemes()
                .get(OpenApiConfig.BEARER_AUTH);

        // Then
        assertThat(scheme).usingRecursiveComparison().isEqualTo(new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
    }
}
