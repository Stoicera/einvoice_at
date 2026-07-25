package com.stoicera.einvoice.app.security;

import java.util.UUID;

/**
 * No API key with the given id exists for the calling tenant — either it never existed, or it
 * belongs to somebody else. One indistinguishable condition on purpose: a caller must not be able
 * to probe whether an id exists under another tenant.
 *
 * <p>A domain exception, mapped to 404 {@code api-key-not-found} by {@code ApiExceptionHandler},
 * exactly like {@code InvoiceNotFoundException} and {@code ReportNotFoundException}. It replaces
 * the {@code ResponseStatusException} {@code ApiKeyService} used to throw: that smuggled a Spring
 * <em>Web</em> type (and an HTTP status decision) into a service, and left this condition speaking
 * the framework's generic {@code not-found} type instead of the per-condition slug ADR-0006
 * promises. An ArchUnit rule now keeps {@code ResponseStatusException} out of everything but the
 * API layer.
 */
public class ApiKeyNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ApiKeyNotFoundException(UUID id) {
    super("No API key for this tenant with id: " + id);
  }
}
