package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.audit.AuditAction;
import com.stoicera.einvoice.app.audit.AuditService;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Application service behind the API-key management endpoints: mint, list, revoke — all scoped to
 * the caller's tenant. Extracted from {@code ApiKeyController} (SPEC §2, task 10): controllers
 * reach persistence only through services/security components, never repositories directly. Sits in
 * {@code ..app.security..}, alongside {@link ApiKeys} and {@link CurrentTenant}, since API keys are
 * a security concern rather than a business one (unlike {@code InvoiceService}/{@code
 * ReportService} in their own {@code ..app.invoice../..app.report..} packages).
 *
 * <p>Behaviour is unchanged from the pre-extraction controller: this is a mechanical move, not a
 * redesign.
 */
@Service
public class ApiKeyService {

  private final ApiKeyRepository apiKeys;
  private final AuditService audit;

  public ApiKeyService(ApiKeyRepository apiKeys, AuditService audit) {
    this.apiKeys = apiKeys;
    this.audit = audit;
  }

  /**
   * Result of minting a key: the persisted entity and the freshly generated key material (whose
   * {@code plaintext} the caller must render exactly once).
   */
  public record CreatedKey(ApiKeyEntity entity, ApiKeys.GeneratedKey generated) {}

  /** Mints a new key for {@code tenantId} and records the {@code API_KEY_CREATED} audit event. */
  @Transactional
  public CreatedKey create(UUID tenantId, String name) {
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    ApiKeyEntity saved =
        apiKeys.save(new ApiKeyEntity(tenantId, name, generated.keyHash(), generated.prefix()));
    audit.record(tenantId, AuditAction.API_KEY_CREATED, generated.keyHash());
    return new CreatedKey(saved, generated);
  }

  /** Lists {@code tenantId}'s keys (active and revoked), newest first. */
  public List<ApiKeyEntity> list(UUID tenantId) {
    return apiKeys.findByTenantIdOrderByCreatedAtDesc(tenantId);
  }

  /**
   * Revokes one of {@code tenantId}'s keys and records the {@code API_KEY_REVOKED} audit event.
   *
   * @throws ResponseStatusException 404 if no key with {@code id} exists for this tenant
   */
  @Transactional
  public void revoke(UUID tenantId, UUID id) {
    ApiKeyEntity key =
        apiKeys
            .findByIdAndTenantId(id, tenantId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
    key.revoke(Instant.now());
    apiKeys.save(key);
    audit.record(tenantId, AuditAction.API_KEY_REVOKED, key.getKeyHash());
  }
}
