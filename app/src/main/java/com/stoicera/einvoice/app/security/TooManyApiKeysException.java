package com.stoicera.einvoice.app.security;

/**
 * The tenant already holds the maximum number of <em>active</em> API keys.
 *
 * <p>Key creation was unbounded before the M3 hostile review: an authenticated caller could mint
 * rows indefinitely, which is both an unbounded-growth vector and a bad answer to "which of these
 * credentials is still in use?". Revoked keys do not count towards the cap — their rows are kept
 * for the audit trail, not for use — so a tenant at the limit gets room again by revoking, never by
 * having history deleted.
 *
 * <p>Mapped to 409 {@code api-key-limit-reached}: a conflict with the tenant's current state that
 * the caller can resolve, not a malformed request.
 */
public class TooManyApiKeysException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int limit;

  public TooManyApiKeysException(int limit) {
    super("Tenant already holds the maximum of " + limit + " active API keys");
    this.limit = limit;
  }

  public int getLimit() {
    return limit;
  }
}
