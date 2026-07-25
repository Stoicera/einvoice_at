package com.stoicera.einvoice.validation;

/**
 * The invoice document formats this platform recognises, as determined from a document's root
 * element namespace.
 *
 * <p>This enum is the dispatch seam ADR-0004 Entscheidung 10 deferred to M4. It turned out to be
 * the shape the problem actually has: what a caller needs from an unknown upload is not "give me a
 * strategy object" but "tell me what this is", after which the right validator, the right reader
 * and the right mapper all follow. Keying that on the detected format keeps the decision in one
 * place and lets the compiler check that every consumer handles every case.
 *
 * <p>{@link #UNKNOWN} is a real member rather than an absent value, because "we could not identify
 * this document" is a reportable outcome with its own rule id, not an error condition.
 */
public enum DocumentFormat {

  /** ebInterface 6.1 — the Austrian national format. */
  EBINTERFACE_61("ebinterface-6.1"),

  /** UBL 2.1 {@code ubl:Invoice} under Peppol BIS Billing 3.0 (EN 16931 type code 380). */
  UBL_INVOICE("ubl-invoice-2.1"),

  /** UBL 2.1 {@code ubl:CreditNote} under Peppol BIS Billing 3.0 (EN 16931 type code 381). */
  UBL_CREDIT_NOTE("ubl-creditnote-2.1"),

  /** The root namespace matched no supported format. */
  UNKNOWN("unknown");

  private final String sourceFormat;

  DocumentFormat(String sourceFormat) {
    this.sourceFormat = sourceFormat;
  }

  /**
   * The stable identifier this format is reported as in a {@code ValidationReport}'s {@code
   * sourceFormat}. Part of the API's wire contract — persisted in the {@code report} table and
   * returned to callers — so these strings are renamed only with a migration.
   */
  public String sourceFormat() {
    return sourceFormat;
  }

  /** Whether this format is one of the two UBL billing documents. */
  public boolean isUbl() {
    return this == UBL_INVOICE || this == UBL_CREDIT_NOTE;
  }
}
