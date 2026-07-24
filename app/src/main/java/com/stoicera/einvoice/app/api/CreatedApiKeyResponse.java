package com.stoicera.einvoice.app.api;

import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.security.ApiKeys;
import java.time.Instant;
import java.util.UUID;

/**
 * Response to key creation. Uniquely, it carries the {@code key} plaintext — this is the one and
 * only time it is ever returned; it is not stored and cannot be retrieved again. The listing
 * response ({@link ApiKeyResponse}) never includes it.
 */
public record CreatedApiKeyResponse(
    UUID id, String name, String prefix, Instant createdAt, String key) {

  static CreatedApiKeyResponse of(ApiKeyEntity entity, ApiKeys.GeneratedKey generated) {
    return new CreatedApiKeyResponse(
        entity.getId(),
        entity.getName(),
        entity.getPrefix(),
        entity.getCreatedAt(),
        generated.plaintext());
  }
}
