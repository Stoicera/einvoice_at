package com.stoicera.einvoice.app.audit;

/**
 * The business actions the audit log records. Persisted by name into {@code audit_event.action}
 * (varchar 64), so the constant names are part of the data contract — rename with a migration,
 * never casually.
 */
public enum AuditAction {
  /** A tenant invoice was created ({@code POST /api/v1/invoices}, T6). */
  INVOICE_CREATED,
  /** A validation run was executed ({@code POST /api/v1/validate}, T7). */
  VALIDATION_RUN,
  /** A tenant API key was minted ({@code POST /api/v1/api-keys}). */
  API_KEY_CREATED,
  /** A tenant API key was revoked ({@code DELETE /api/v1/api-keys/{id}}). */
  API_KEY_REVOKED
}
