package com.stoicera.einvoice.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of tenant API keys.
 *
 * <p>A key is {@code eiv_} followed by at least 32 characters of URL-safe random drawn from {@link
 * SecureRandom}. Only the SHA-256 hash (64 lowercase hex chars) is ever stored — the plaintext is
 * returned to the caller exactly once, at creation, and is unrecoverable afterwards. A short {@code
 * prefix} (the first eight characters) is kept alongside the hash so a key can be recognised in a
 * listing without being usable.
 *
 * <p>Utility class: all behaviour is static and side-effect-free apart from consuming randomness.
 */
public final class ApiKeys {

  /** Human-recognisable, non-secret marker every key starts with. */
  public static final String PREFIX = "eiv_";

  /**
   * Bytes of randomness in the secret part; 32 bytes ≈ 43 URL-safe chars, well over the 32 minimum.
   */
  private static final int SECRET_BYTES = 32;

  /** Characters of the plaintext kept as the display prefix. */
  private static final int DISPLAY_PREFIX_LENGTH = 8;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final HexFormat HEX = HexFormat.of(); // lowercase, no delimiter

  private ApiKeys() {}

  /**
   * A freshly minted key: its {@code plaintext} (shown to the caller once), the {@code keyHash} to
   * persist, and the {@code prefix} to persist for display. The plaintext lives only in this record
   * and must never be logged or stored.
   */
  public record GeneratedKey(String plaintext, String keyHash, String prefix) {}

  /** Generates a new key with its hash and display prefix. */
  public static GeneratedKey generate() {
    byte[] secret = new byte[SECRET_BYTES];
    RANDOM.nextBytes(secret);
    String plaintext = PREFIX + URL_ENCODER.encodeToString(secret);
    String prefix = plaintext.substring(0, DISPLAY_PREFIX_LENGTH);
    return new GeneratedKey(plaintext, sha256Hex(plaintext), prefix);
  }

  /** Returns the SHA-256 of {@code value} (UTF-8) as 64 lowercase hex characters. */
  public static String sha256Hex(String value) {
    return HEX.formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a mandated algorithm on every JVM; its absence is a broken runtime, not a
      // recoverable condition.
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
