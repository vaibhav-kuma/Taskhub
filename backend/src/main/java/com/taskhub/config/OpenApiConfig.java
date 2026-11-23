package com.taskhub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI taskHubOpenApi(AppProperties properties) {
        final String scheme = "bearer-jwt";
        return new OpenAPI()
                .info(new Info()
                        .title("TaskHub API")
                        .version("1.0.0")
                        .description("""
                                Project and task tracking API.

                                **Authentication** — call `POST /api/v1/auth/login` with your credentials, then
                                click *Authorize* and paste the returned `accessToken`. Access tokens live for
                                15 minutes; use `POST /api/v1/auth/refresh` to rotate the refresh token and
                                obtain a new access token.

                                **Pagination** — every list endpoint accepts `page` (0-based), `size` (1-100),
                                `sort` (allow-listed field) and `direction` (`asc`/`desc`).
                                """)
                        .contact(new Contact().name("TaskHub Engineering").email("engineering@taskhub.local"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("/").description("Current host")))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the `accessToken` value only — Swagger adds the `Bearer ` prefix.")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
