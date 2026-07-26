package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.util.StringUtils;

/**
 * The platform's security policy, in <strong>two filter chains</strong>.
 *
 * <h2>Why two (ADR-0009)</h2>
 *
 * <p>Until M5 there was one, and it was right: {@code app} served only a stateless REST API. M5
 * adds a browser surface, and the two have opposite requirements — an API client wants {@code 401}
 * + {@code problem+json} where a browser wants a redirect to the login page; an API request must
 * create no session where a form request needs one; CSRF protection is mandatory for a cookie
 * session (ENGINEERING_STANDARDS §4) and meaningless for a bearer-token call. Expressing that in
 * one chain would mean a condition per difference, and every such condition is a place where a
 * later change hits the wrong half.
 *
 * <ul>
 *   <li>{@link #apiSecurityFilterChain} — {@code @Order(1)}, matches {@code /api/**} plus the
 *       health probes and OpenAPI paths. <strong>Byte-for-byte the M4 policy</strong>: stateless,
 *       CSRF off, OAuth2 resource server or {@code X-Api-Key}.
 *   <li>{@link #webSecurityFilterChain} — {@code @Order(2)}, the catch-all. Session, CSRF on,
 *       {@code oauth2Login} against Keycloak.
 * </ul>
 *
 * <p><strong>The order is load-bearing.</strong> A new API path placed <em>outside</em> {@code
 * /api/**} would fall through to the web chain and answer a missing credential with a login
 * redirect instead of a 401. {@code SecurityChainRoutingIT} asserts the split rather than trusting
 * the convention.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /** Everything the stateless API chain owns. Anything else belongs to the browser chain. */
  private static final String[] API_PATHS = {
    "/api/**", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
  };

  /**
   * The stateless REST API — unchanged from M4 except for being scoped to {@link #API_PATHS} by an
   * explicit {@code securityMatcher} instead of being the only chain there is.
   */
  @Bean
  @Order(1)
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      ApiKeyRepository apiKeys,
      JwtDecoder jwtDecoder,
      RateLimitFilter rateLimitFilter)
      throws Exception {
    http.securityMatcher(API_PATHS)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(HttpMethod.POST, "/api/v1/validate")
                    .permitAll()
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    // API-key management is OAuth2-only. JWT logins carry ROLE_USER; API-key
                    // authentications carry ROLE_API_KEY, so this denies keys in the security layer
                    // itself rather than trusting controller code to re-check.
                    .requestMatchers("/api/v1/api-keys/**")
                    .hasRole("USER")
                    // Erasure is OAuth2-only for the SAME reason, and with more at stake (M5
                    // hostile review, finding F1). This endpoint deletes every invoice, report,
                    // key and audit event a tenant owns, irreversibly, with no backup — while the
                    // rule above already says a machine credential may not so much as LIST the
                    // keys. Leaving erasure on the .anyRequest() catch-all meant an X-Api-Key,
                    // the credential class that lives in ERP config files and CI variables, could
                    // do the single most destructive thing this platform can do. Invoices sharpen
                    // it rather than soften it: RetentionService never expires them because § 132
                    // BAO obliges the business to keep them seven years, so a leaked integration
                    // key must not be able to destroy legally mandated records either.
                    //
                    // Erasing therefore requires an interactive identity: the dashboard's typed
                    // "LÖSCHEN" confirmation, or a JWT. TenantErasureApiIT asserts the whole
                    // matrix — anonymous 401, API key 403, login 204.
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/tenant")
                    .hasRole("USER")
                    .anyRequest()
                    .authenticated())
        // X-Api-Key is resolved before the bearer-token filter.
        .addFilterBefore(new ApiKeyAuthFilter(apiKeys), BearerTokenAuthenticationFilter.class)
        // Placed AFTER AuthorizationFilter, not alongside the authentication filters above:
        // RateLimitFilter needs the request's FINAL authentication state (every authentication
        // mechanism, including Spring Security's own anonymous-authentication filter, has run by
        // then) and only ever sees requests that already cleared authorization — see
        // RateLimitFilter's own Javadoc for the full placement rationale.
        .addFilterAfter(rateLimitFilter, AuthorizationFilter.class)
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

  /**
   * The browser surface (SPEC §5): the public validator and the authenticated dashboard.
   *
   * <p>Three deliberate differences from the API chain, each a consequence of a cookie session
   * existing at all:
   *
   * <ul>
   *   <li><strong>CSRF protection is on</strong> — the requirement ENGINEERING_STANDARDS §4 states
   *       and that the API chain legitimately does not need. Every state-changing form carries the
   *       token; Thymeleaf adds it to {@code <form method="post">} automatically.
   *   <li><strong>A session is created</strong> (the framework default), because the
   *       authorization-code flow has nowhere else to keep the authenticated principal.
   *   <li><strong>{@code oauth2Login}</strong> — a browser gets Keycloak's login page and comes
   *       back with a code, rather than being told to go and find a bearer token.
   * </ul>
   *
   * <p>The public routes are exactly the ones SPEC §4 names — {@code /}, {@code /validator} and its
   * upload — plus the static assets they need. Everything under {@code /app/**} requires a login.
   */
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  SecurityFilterChain webSecurityFilterChain(
      HttpSecurity http,
      RateLimitFilter rateLimitFilter,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations)
      throws Exception {
    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/", "/validator")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/validator/pruefen")
                    .permitAll()
                    // The public report view offers "Erklären" too; it explains a finding posted
                    // back
                    // to it, holds no server state, and is rate-limited like the upload itself.
                    .requestMatchers(HttpMethod.POST, "/validator/erklaeren")
                    .permitAll()
                    // The static assets the public pages need. /favicon.ico is listed although no
                    // such file exists and none is intended: the icon is favicon.svg, declared in
                    // the layout, but browsers and bookmark handlers still probe /favicon.ico
                    // unprompted, and a probe that fell through to `.anyRequest().authenticated()`
                    // would answer a login redirect instead of a plain 404 — noise in the access
                    // log and, with a login configured, a redirect chain for a missing icon.
                    .requestMatchers(
                        "/app.css", "/app.js", "/favicon.svg", "/favicon.ico", "/robots.txt")
                    .permitAll()
                    // Boot's error dispatch forwards to /error; the AuthorizationFilter runs on
                    // that
                    // dispatch too, so /error must be permitted or a permitted path's 404 would be
                    // rewritten to a 401 for an anonymous caller.
                    .requestMatchers("/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // Response headers the API chain does not need and this one does (M5 hostile review, F8).
        // Spring's defaults — nosniff, X-Frame-Options: DENY, no-store — stay in place and are
        // enough for a JSON API; an HTML surface that renders LLM output and assigns
        // server-rendered
        // markup into innerHTML wants the control that still holds when an escaping bug gets
        // through.
        //
        // The policy is as narrow as this UI actually is: it vendors nothing, loads from no CDN and
        // uses no inline script (ADR-0009), so 'self' covers script and style with NO
        // 'unsafe-inline' anywhere — the ten inline style="…" attributes the templates carried were
        // replaced by five stylesheet rules for exactly this, because an inline style attribute is
        // what forces 'unsafe-inline' into style-src and a policy with it is barely a policy.
        // default-src 'none' makes every source type opt-in rather than inherited, so a future
        // third-party <img src> is a console error here rather than a silent new dependency.
        // img-src
        // allows data: for inline SVG; connect-src 'self' is what app.js's fetch needs, no more.
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'none'; "
                                    + "script-src 'self'; "
                                    + "style-src 'self'; "
                                    + "img-src 'self' data:; "
                                    + "font-src 'self'; "
                                    + "connect-src 'self'; "
                                    + "form-action 'self'; "
                                    + "base-uri 'self'; "
                                    + "frame-ancestors 'none'"))
                    // The public validator is a German-SEO landing page, so it has outbound links;
                    // without this its full URL travels to whatever a visitor clicks through to.
                    .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
        .logout(logout -> logout.logoutSuccessUrl("/").permitAll())
        // Same instance as the API chain (a bean, not a `new`), so one caller cannot double their
        // anonymous allowance by alternating between the UI upload and the API endpoint.
        .addFilterAfter(rateLimitFilter, AuthorizationFilter.class);

    // oauth2Login only when a client registration actually exists. Calling it unconditionally would
    // fail context startup wherever no OAuth2 client is configured — which is every persistence and
    // API integration test, none of which logs in through a browser. This is the same mistake the
    // M3
    // fix wave made once with the JWT validator list (built unconditionally, broke every context
    // with no issuer) and the same fix: ask whether the collaborator is there.
    //
    // Without a registration the public pages still work and /app/** is simply refused, rather than
    // the whole application failing to start.
    if (clientRegistrations.getIfAvailable() != null) {
      http.oauth2Login(login -> login.defaultSuccessUrl("/app", true));
    }
    return http.build();
  }

  /**
   * One rate limiter shared by both chains. A bean rather than two {@code new} instances on
   * purpose: separate instances would mean separate bucket maps, and an anonymous caller would get
   * one full allowance per surface for the same work.
   */
  @Bean
  RateLimitFilter rateLimitFilter(
      @Value("${app.rate-limit.validate.capacity}") long rateLimitCapacity,
      @Value("${app.rate-limit.validate.refill-per-minute}") long rateLimitRefillPerMinute,
      @Value("${app.rate-limit.convert.capacity}") long convertRateLimitCapacity,
      @Value("${app.rate-limit.convert.refill-per-minute}") long convertRateLimitRefillPerMinute,
      @Value("${app.rate-limit.explain.capacity}") long explainRateLimitCapacity,
      @Value("${app.rate-limit.explain.refill-per-minute}") long explainRateLimitRefillPerMinute) {
    return new RateLimitFilter(
        rateLimitCapacity,
        rateLimitRefillPerMinute,
        convertRateLimitCapacity,
        convertRateLimitRefillPerMinute,
        explainRateLimitCapacity,
        explainRateLimitRefillPerMinute);
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
    // It rejects an empty list outright, so with neither issuer nor audience configured (the
    // persistence ITs, which send no tokens at all) the decoder is left with the default validator
    // NimbusJwtDecoder builds for itself, which is the same set.
    if (!additional.isEmpty()) {
      decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(additional));
    }
    return decoder;
  }

  /** Requires {@code aud} to contain the configured value; a missing {@code aud} fails. */
  private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
    return new JwtClaimValidator<List<String>>(
        JwtClaimNames.AUD, claim -> claim != null && claim.contains(audience));
  }
}
