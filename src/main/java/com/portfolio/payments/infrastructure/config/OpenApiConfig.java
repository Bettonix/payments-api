package com.portfolio.payments.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuração OpenAPI 3.0 / Swagger UI para a Payments API.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentsOpenAPI(
        @Value("${app.openapi.server-url:http://localhost:8181}") String serverUrl,
        @Value("${app.keycloak.token-url:http://localhost:8080/realms/payments/protocol/openid-connect/token}") String tokenUrl
    ) {
        String oauthSchemeName = "KeycloakOAuth2";
        String bearerSchemeName = "BearerAuth";

        Scopes scopes = new Scopes()
            .addString("payments:read", "Permissão para consulta de pagamentos")
            .addString("payments:write", "Permissão para criação e transição de pagamentos")
            .addString("payments:admin", "Permissão para operações de liquidação (settle) e administrativas");

        OAuthFlow clientCredentialsFlow = new OAuthFlow()
            .tokenUrl(tokenUrl)
            .scopes(scopes);

        return new OpenAPI()
            .info(new Info()
                .title("Payments API")
                .description("API financeira de pagamentos com garantia estrita de idempotência, Outbox pattern e observabilidade.")
                .version("v1.0.0")
                .contact(new Contact().name("Engenharia de Pagamentos").email("payments@portfolio.com"))
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(
                new Server().url(serverUrl).description("Ambiente Local"),
                new Server().url("http://127.0.0.1:8181").description("Ambiente Local Loopback")
            ))
            .components(new Components()
                .addSecuritySchemes(oauthSchemeName, new SecurityScheme()
                    .name(oauthSchemeName)
                    .type(SecurityScheme.Type.OAUTH2)
                    .description("Autenticação OAuth2 via Keycloak (Client Credentials Flow)")
                    .flows(new OAuthFlows().clientCredentials(clientCredentialsFlow)))
                .addSecuritySchemes(bearerSchemeName, new SecurityScheme()
                    .name(bearerSchemeName)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Token JWT Bearer do Keycloak")))
            .addSecurityItem(new SecurityRequirement().addList(oauthSchemeName))
            .addSecurityItem(new SecurityRequirement().addList(bearerSchemeName));
    }
}
