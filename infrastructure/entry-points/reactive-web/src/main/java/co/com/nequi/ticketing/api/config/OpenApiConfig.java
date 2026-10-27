package co.com.nequi.ticketing.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security scheme of the OpenAPI document: the protected routes reference it by name.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    private static final String BEARER = "bearer";
    private static final String JWT = "JWT";

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme(BEARER).bearerFormat(JWT)));
    }
}
