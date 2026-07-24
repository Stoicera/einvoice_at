package com.stoicera.einvoice.app.security;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Resolves the tenant for an authenticated principal, creating it on first sight. A Keycloak login
 * has no tenant row until its first authenticated request; this service maps the stable {@code sub}
 * claim to a {@link TenantEntity}, provisioning one lazily.
 *
 * <p>Two concurrent first requests for the same subject would both find no row and both try to
 * insert. The boring, database-authoritative resolution is used: the {@code
 * unique(external_subject)} constraint lets exactly one insert win; the loser catches the integrity
 * violation and adopts the winner's row. No application-level locking.
 */
@Service
public class TenantProvisioningService {

  private final TenantRepository tenants;

  public TenantProvisioningService(TenantRepository tenants) {
    this.tenants = tenants;
  }

  /**
   * Returns the tenant for {@code externalSubject}, creating it with {@code displayName} if it does
   * not yet exist. Idempotent and safe under concurrent first requests.
   */
  public TenantEntity provision(String externalSubject, String displayName) {
    return tenants
        .findByExternalSubject(externalSubject)
        .orElseGet(() -> insertOrAdopt(externalSubject, displayName));
  }

  private TenantEntity insertOrAdopt(String externalSubject, String displayName) {
    try {
      // saveAndFlush (not save) so the unique-constraint violation surfaces here, inside the try,
      // rather than on a later flush outside our reach. Each repository call is its own transaction
      // (this method is deliberately not @Transactional), so the failed insert rolls back on its
      // own
      // and the re-read below runs in a fresh transaction that sees the committed winner.
      return tenants.saveAndFlush(new TenantEntity(externalSubject, displayName));
    } catch (DataIntegrityViolationException race) {
      return tenants
          .findByExternalSubject(externalSubject)
          .orElseThrow(() -> race); // not the unique-key race we expected — surface it
    }
  }
}
