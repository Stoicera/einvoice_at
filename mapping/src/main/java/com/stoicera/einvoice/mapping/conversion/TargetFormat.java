package com.stoicera.einvoice.mapping.conversion;

/**
 * The formats a canonical invoice can be written to.
 *
 * <p>Deliberately a small enum of this module's own rather than a reference to the {@code
 * validation} module's {@code DocumentFormat}: mapping does not depend on validation, and never
 * will — validation depends on the formats, and canonical mapping is a separate concern that would
 * invert if it reached the other way (SPEC §2, pinned by {@code ValidationArchitectureTest}).
 *
 * <p>UBL is one member here where validation has two, because the choice between {@code
 * ubl:Invoice} and {@code ubl:CreditNote} is not the caller's: it follows from BT-3 on the invoice
 * itself.
 */
public enum TargetFormat {

  /** ebInterface 6.1. */
  EBINTERFACE_61("ebinterface-6.1"),

  /** UBL 2.1 under Peppol BIS Billing 3.0 — invoice or credit note, decided by BT-3. */
  UBL("ubl-2.1");

  private final String id;

  TargetFormat(String id) {
    this.id = id;
  }

  /** The stable identifier this format is reported as in a {@link ConversionReport}. */
  public String id() {
    return id;
  }
}
