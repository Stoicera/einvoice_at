package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * A key as shown in the management listing: id, label, the non-secret display prefix, when it was
 * created and (if applicable) revoked. Neither the plaintext nor the stored hash is exposed.
 */
public record ApiKeyResponse(
    UUID id, String name, String prefix, Instant createdAt, Instant revokedAt, boolean revoked) {

  static ApiKeyResponse of(ApiKeyEntity entity) {
    return new ApiKeyResponse(
        entity.getId(),
        entity.getName(),
        entity.getPrefix(),
        entity.getCreatedAt(),
        entity.getRevokedAt(),
        entity.isRevoked());
  }
}
