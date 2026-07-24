package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.util.StringUtils;

/**
 * The stateless security policy for the REST API.
 *
 * <p>Two authentication mechanisms sit side by side: an OAuth2 resource server validating Keycloak
 * JWTs, and the {@link ApiKeyAuthFilter} resolving {@code X-Api-Key} headers ahead of the bearer
 * filter. A request presents one or the other.
 *
 * <p>Authorization is expressed in the rule set, not scattered through controllers:
 *
 * <ul>
 *   <li>public: {@code POST /api/v1/validate} (the anonymous validator, endpoint arrives in T7),
 *       the health probes, and the OpenAPI docs/UI (springdoc arrives in T9);
 *   <li>{@code /api/v1/api-keys/**}: JWT logins only ({@code ROLE_USER}) — an API key ({@code
 *       ROLE_API_KEY}) must never mint or revoke API keys;
 *   <li>everything else: authenticated.
 * </ul>
 *
 * <p>{@link RateLimitFilter} adds a per-IP token bucket in front of anonymous calls to the public
 * validator only (T8, SPEC section 4) — see that class's Javadoc for the full rationale.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiKeyRepository apiKeys,
      JwtDecoder jwtDecoder,
      @Value("${app.rate-limit.validate.capacity}") long rateLimitCapacity,
      @Value("${app.rate-limit.validate.refill-per-minute}") long rateLimitRefillPerMinute)
      throws Exception {
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/validate")
                    .permitAll()
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    // Boot's error dispatch forwards to /error; the AuthorizationFilter runs on
                    // that
                    // dispatch too, so /error must be permitted or a permitted path's 404 would be
                    // rewritten to a 401 for an anonymous caller.
                    .requestMatchers("/error")
                    .permitAll()
                    // API-key management is OAuth2-only. JWT logins carry ROLE_USER; API-key
                    // authentications carry ROLE_API_KEY, so this denies keys in the security layer
                    // itself rather than trusting controller code to re-check.
                    .requestMatchers("/api/v1/api-keys/**")
                    .hasRole("USER")
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .authenticated())
        // X-Api-Key is resolved before the bearer-token filter.
        .addFilterBefore(new ApiKeyAuthFilter(apiKeys), BearerTokenAuthenticationFilter.class)
        // Placed AFTER AuthorizationFilter, not alongside the authentication filters above:
        // RateLimitFilter needs the request's FINAL authentication state (every authentication
        // mechanism, including Spring Security's own anonymous-authentication filter, has run by
        // then) and only ever sees requests that already cleared authorization — see
        // RateLimitFilter's own Javadoc for the full placement rationale.
        .addFilterAfter(
            new RateLimitFilter(rateLimitCapacity, rateLimitRefillPerMinute),
            AuthorizationFilter.class)
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(
                    jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtConverter())))
        // Stateless: no HTTP session is created; every request re-authenticates from its token or
        // key. CSRF defends cookie-session browsers, and this API keeps no cookie session, so it is
        // disabled here (rationale recorded in ADR-0006 — auth and API security). Spring's default
        // security response headers are left in place.
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  private JwtAuthenticationConverter jwtConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    // Every validated Keycloak login counts as a dashboard user for authorization. Fine-grained
    // realm/client roles are not modelled in M3; ROLE_USER exists only to distinguish an
    // interactive
    // login from an API key, which is what the /api-keys rule keys on.
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> List.of(new SimpleGrantedAuthority("ROLE_USER")));
    return converter;
  }

  /**
   * The JWT decoder, built from the JWKS endpoint so Nimbus fetches keys lazily (on first token
   * validation): the context boots with no network round-trip, and tests that present no token need
   * no IdP. With no issuer and no explicit JWKS (a persistence IT), a non-resolving placeholder URL
   * is used — it is never contacted because those tests send no tokens.
   *
   * <p>Three validators, layered onto Nimbus's own signature check:
   *
   * <ul>
   *   <li>the framework defaults, always — most importantly {@code exp}/{@code nbf} with Spring's
   *       standard clock skew;
   *   <li>{@code iss}, whenever an issuer is configured: a token must come from that realm;
   *   <li>{@code aud}, whenever {@code app.oauth2.audience} is set. This closes the limit ADR-0006
   *       recorded and called "the first hardening candidate": signature + issuer alone prove a
   *       token is genuine, not that it was minted <em>for this API</em>, so without it any client
   *       in the realm could present its own token and be authenticated as {@code ROLE_USER}. It
   *       stays opt-in and off by default, because switching it on unconditionally would break a
   *       single-audience dev realm whose tokens carry a different {@code aud}; each behaviour is
   *       pinned by {@code JwtDecoderTest}.
   * </ul>
   */
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${app.oauth2.issuer-uri:}") String issuerUri,
      @Value("${app.oauth2.jwk-set-uri:}") String jwkSetUri,
      @Value("${app.oauth2.audience:}") String audience) {
    String effectiveJwkSetUri =
        StringUtils.hasText(jwkSetUri)
            ? jwkSetUri
            : StringUtils.hasText(issuerUri)
                ? issuerUri + "/protocol/openid-connect/certs"
                : "http://authserver.invalid/protocol/openid-connect/certs";
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(effectiveJwkSetUri).build();

    List<OAuth2TokenValidator<Jwt>> additional = new ArrayList<>();
    if (StringUtils.hasText(issuerUri)) {
      additional.add(new JwtIssuerValidator(issuerUri));
    }
    if (StringUtils.hasText(audience)) {
      additional.add(audienceValidator(audience));
    }
    // createDefaultWithValidators keeps the framework's own default validators and appends ours —
    // the same set createDefaultWithIssuer would have produced when only an issuer is configured.
    decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(additional));
    return decoder;
  }

  /** Requires {@code aud} to contain the configured value; a missing {@code aud} fails. */
  private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
    return new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD, claim -> claim != null && claim.contains(audience));
  }
}
