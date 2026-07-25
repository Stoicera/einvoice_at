package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The token-validation contract of {@link SecurityConfig#jwtDecoder}, asserted directly against the
 * bean.
 *
 * <p><b>Why this test exists.</b> Before it, the whole suite's only OAuth2 coverage was the happy
 * path — {@code AuthMatrixIT} proved a real Keycloak token authenticates, and nothing proved any
 * token is ever <em>refused</em>. The decoder is the single control keeping an attacker-minted JWT
 * out of a multi-tenant billing API, and it could have regressed to a permissive one without a
 * single test turning red (M3 hostile review, F2).
 *
 * <p><b>How it works without a Keycloak.</b> An RSA key pair is generated in-process and its public
 * half published as a JWKS by a throwaway {@link HttpServer} on a loopback port; the decoder is
 * pointed at that URL. Tokens are then minted here with Nimbus — which means this test can do what
 * an IT against a real IdP cannot: mint a token with a <em>wrong issuer</em>, an <em>expired</em>
 * {@code exp}, a <em>foreign signature</em>, or a <em>foreign audience</em>, each varied one at a
 * time. It is a plain {@code *Test} (Surefire, no Docker), so it runs in the fast feedback loop.
 */
class JwtDecoderTest {

  private static final String ISSUER = "https://idp.example.test/realms/einvoice";
  private static final String AUDIENCE = "einvoice-api";
  private static final String KEY_ID = "test-signing-key";

  private static HttpServer jwksServer;
  private static RSAKey signingKey;
  private static String jwkSetUri;

  @BeforeAll
  static void publishJwks() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

    // Port 0: the OS picks a free port, so parallel builds never collide.
    jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwksServer.createContext(
        "/jwks",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, jwks.length);
          try (var body = exchange.getResponseBody()) {
            body.write(jwks);
          }
        });
    jwksServer.start();
    jwkSetUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks";
  }

  @AfterAll
  static void stopJwks() {
    jwksServer.stop(0);
  }

  @Test
  void aTokenFromTheConfiguredIssuerIsAccepted() throws Exception {
    Jwt jwt = decoder(ISSUER, "").decode(token(ISSUER, AUDIENCE, validExpiry(), signingKey));

    assertThat(jwt.getSubject()).isEqualTo("user-1");
    assertThat(jwt.getIssuer()).hasToString(ISSUER);
  }

  @Test
  void aTokenFromAnotherIssuerIsRejected() throws Exception {
    // Correctly signed by the key this deployment trusts, but minted by a different realm. Without
    // the issuer validator this token would authenticate.
    String foreignIssuer = "https://attacker.example.test/realms/einvoice";
    String jwt = token(foreignIssuer, AUDIENCE, validExpiry(), signingKey);

    assertThatThrownBy(() -> decoder(ISSUER, "").decode(jwt))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("iss");
  }

  @Test
  void anExpiredTokenIsRejected() throws Exception {
    // Well past JwtTimestampValidator's 60-second default clock skew.
    String jwt = token(ISSUER, AUDIENCE, Instant.now().minus(Duration.ofMinutes(10)), signingKey);

    assertThatThrownBy(() -> decoder(ISSUER, "").decode(jwt))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void aTokenSignedByAnUnknownKeyIsRejected() throws Exception {
    // An attacker's own key pair, announcing an unknown key id: nothing in the published JWKS
    // matches, so no verification key can even be selected.
    RSAKey foreignKey = new RSAKeyGenerator(2048).keyID("attacker-key").generate();
    String jwt = token(ISSUER, AUDIENCE, validExpiry(), foreignKey);

    assertThatThrownBy(() -> decoder(ISSUER, "").decode(jwt)).isInstanceOf(JwtException.class);
  }

  @Test
  void aTokenSignedByAForeignKeyImpersonatingTheRealKeyIdIsRejected() throws Exception {
    // The sharper variant: the attacker's key claims the *same* kid as the real signing key, so key
    // selection succeeds and only the cryptographic signature check stands between this token and
    // acceptance. This is what proves the signature is genuinely verified, not merely kid-matched.
    RSAKey impostor = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    String jwt = token(ISSUER, AUDIENCE, validExpiry(), impostor);

    assertThatThrownBy(() -> decoder(ISSUER, "").decode(jwt)).isInstanceOf(JwtException.class);
  }

  @Test
  void garbageThatIsNotAJwtIsRejected() {
    assertThatThrownBy(() -> decoder(ISSUER, "").decode("not-a-token"))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void withNoAudienceConfiguredAnyAudienceIsAccepted() throws Exception {
    // The documented default and the pre-M3-review behaviour: audience checking is opt-in, so a
    // single-audience dev realm needs no extra configuration. Pinned so the default cannot drift
    // silently into "rejects everything" for an existing deployment.
    String jwt = token(ISSUER, "some-other-client", validExpiry(), signingKey);

    assertThatCode(() -> decoder(ISSUER, "").decode(jwt)).doesNotThrowAnyException();
  }

  @Test
  void withNeitherIssuerNorAudienceConfiguredTheDecoderStillBuildsAndStillChecksExpiry()
      throws Exception {
    // The persistence ITs run with no issuer and no audience — they send no tokens at all, so the
    // decoder only has to exist. It very nearly did not: JwtValidators.createDefaultWithValidators
    // rejects an empty list, so building the validator set unconditionally broke every one of
    // those contexts. The default validators must still be in force in this configuration, which
    // the expiry assertion below is what actually proves.
    JwtDecoder permissive = decoder("", "");

    assertThatCode(() -> permissive.decode(token(ISSUER, AUDIENCE, validExpiry(), signingKey)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                permissive.decode(
                    token(
                        ISSUER, AUDIENCE, Instant.now().minus(Duration.ofMinutes(10)), signingKey)))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void withAnAudienceConfiguredATokenMintedForThisApiIsAccepted() throws Exception {
    Jwt jwt = decoder(ISSUER, AUDIENCE).decode(token(ISSUER, AUDIENCE, validExpiry(), signingKey));

    assertThat(jwt.getAudience()).contains(AUDIENCE);
  }

  @Test
  void withAnAudienceConfiguredATokenMintedForAnotherClientIsRejected() throws Exception {
    // The ADR-0006 known limit, now closed when the property is set: a signature-valid,
    // correctly-issued token obtained by any other client in the same realm must not authenticate
    // against this API.
    String jwt = token(ISSUER, "some-other-client", validExpiry(), signingKey);

    assertThatThrownBy(() -> decoder(ISSUER, AUDIENCE).decode(jwt))
        .isInstanceOf(JwtException.class)
        .hasMessageContaining("aud");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static JwtDecoder decoder(String issuerUri, String audience) {
    return new SecurityConfig().jwtDecoder(issuerUri, jwkSetUri, audience);
  }

  private static Instant validExpiry() {
    return Instant.now().plus(Duration.ofMinutes(10));
  }

  /** Mints an RS256 token with the given issuer, audience and expiry, signed by {@code key}. */
  private static String token(String issuer, String audience, Instant expiresAt, RSAKey key)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject("user-1")
            .audience(audience)
            // Derived from the expiry, not from "now": an expired token still has to be internally
            // consistent (iat before exp), or Nimbus rejects it as malformed and the test would
            // pass for the wrong reason — it would never reach the timestamp validator at all.
            .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(5))))
            .expirationTime(Date.from(expiresAt))
            .claim("preferred_username", "testuser")
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  /** Guards against a helper that silently mints unsigned tokens. */
  @Test
  void theTestHelperReallyProducesAThreePartSignedJws() throws Exception {
    assertThat(token(ISSUER, AUDIENCE, validExpiry(), signingKey).split("\\.")).hasSize(3);
    assertThat(List.of(token(ISSUER, AUDIENCE, validExpiry(), signingKey).split("\\.")))
        .noneMatch(String::isEmpty);
  }
}
