package com.stoicera.einvoice.app.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc's document metadata: API info and the two authentication mechanisms {@link
 * com.stoicera.einvoice.app.security.SecurityConfig} accepts.
 *
 * <p>{@code bearerAuth} and {@code apiKeyAuth} are registered as two separate global {@link
 * SecurityRequirement} entries rather than combined into one — in OpenAPI, entries within the
 * top-level {@code security} array are alternatives ("satisfy any one of these"), while schemes
 * combined inside a single entry would instead mean "all of these together", which is not how this
 * API's authentication works: a caller presents a bearer JWT <em>or</em> an API key, never both.
 * {@code POST /api/v1/validate} is the one public endpoint and overrides this default with an empty
 * {@code @SecurityRequirement} on its own operation (see {@link ValidationController}).
 *
 * <p>No explicit server URL is set: springdoc derives it from the incoming request, which keeps the
 * generated document correct across every environment (local dev, CI, and any future deployment)
 * without hardcoding one here.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI einvoiceOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("einvoice-at API")
                // No cheap, already-wired source for the build version (no build-info plugin
                // execution configured, no resource filtering) — a literal is honest and stays in
                // sync manually, same as the other "no defaults, fail loud" choices in this
                // module.
                .version("v1")
                // Kept to what the paths below actually offer. The previous wording announced UBL
                // support as future work — written before M4 and never revisited — while this same
                // document published /api/v1/convert and /api/v1/invoices/{id}/ubl. A description
                // that contradicts its own paths is worse than a terse one, so
                // theApiDescriptionDoesNotContradictTheEndpointsItPublishes now asserts it against
                // the document rather than against a remembered string.
                .description(
                    "REST API for generating, validating and converting Austrian e-invoices."
                        + " Validation covers ebInterface 6.1 against the AT-B2G business rules and"
                        + " Peppol BIS Billing 3.0 (UBL) against the official OpenPeppol rule set,"
                        + " executed unmodified at a pinned version. Conversion runs in both"
                        + " directions through a canonical EN 16931 model and reports what the"
                        + " target format cannot carry."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak-issued OAuth2 access token."))
                .addSecuritySchemes(
                    "apiKeyAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Api-Key")
                        .description("Tenant API key minted via POST /api/v1/api-keys.")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .addSecurityItem(new SecurityRequirement().addList("apiKeyAuth"));
  }
}
