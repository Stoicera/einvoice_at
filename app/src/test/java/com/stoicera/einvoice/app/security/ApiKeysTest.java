package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link ApiKeys}: the generation format, the stability of the SHA-256
 * hash (a known vector), and the fact that only the hash and a short display prefix ever leave the
 * generator — never a reversible copy of the plaintext.
 */
class ApiKeysTest {

  @Test
  void sha256HexIsStableAndMatchesTheKnownEmptyStringVector() {
    // The SHA-256 of the empty string is a fixed, widely published vector — a cheap oracle that
    // proves the hex encoding (lowercase, zero-padded, 64 chars) is correct, not just
    // self-consistent.
    assertThat(ApiKeys.sha256Hex(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    // Stable: same input, same digest, every call.
    assertThat(ApiKeys.sha256Hex("eiv_abc")).isEqualTo(ApiKeys.sha256Hex("eiv_abc"));
    assertThat(ApiKeys.sha256Hex("eiv_abc")).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void generatedKeyHasThePrefixedUrlSafeFormat() {
    ApiKeys.GeneratedKey key = ApiKeys.generate();

    assertThat(key.plaintext()).startsWith("eiv_");
    // "eiv_" (4) + at least 32 chars of URL-safe random secret.
    assertThat(key.plaintext().length()).isGreaterThanOrEqualTo(4 + 32);
    // URL-safe base64 alphabet only, no padding — safe to carry in a header or query with no
    // escaping.
    assertThat(key.plaintext()).matches("eiv_[A-Za-z0-9_-]+");
  }

  @Test
  void generatedKeyExposesOnlyTheHashAndAnEightCharDisplayPrefix() {
    ApiKeys.GeneratedKey key = ApiKeys.generate();

    // The stored hash is the SHA-256 of the plaintext (64 lowercase hex), not the plaintext itself.
    assertThat(key.keyHash()).isEqualTo(ApiKeys.sha256Hex(key.plaintext()));
    assertThat(key.keyHash()).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(key.plaintext());

    // The non-secret prefix is exactly the first 8 chars — enough to recognise a key, useless to
    // use it.
    assertThat(key.prefix()).hasSize(8).isEqualTo(key.plaintext().substring(0, 8));
    assertThat(key.plaintext()).startsWith(key.prefix());
  }

  @Test
  void everyGeneratedKeyIsDistinct() {
    assertThat(ApiKeys.generate().plaintext()).isNotEqualTo(ApiKeys.generate().plaintext());
  }

  @Test
  void persistedEntityCarriesTheHashNotThePlaintext() {
    ApiKeys.GeneratedKey key = ApiKeys.generate();
    ApiKeyEntity entity =
        new ApiKeyEntity(UUID.randomUUID(), "CI key", key.keyHash(), key.prefix());

    // The entity — the only thing that reaches the database — holds the hash and prefix, never a
    // field from which the plaintext could be read back.
    assertThat(entity.getKeyHash()).isEqualTo(key.keyHash());
    assertThat(entity.getPrefix()).isEqualTo(key.prefix());
    assertThat(entity.getKeyHash()).isNotEqualTo(key.plaintext());
  }
}
