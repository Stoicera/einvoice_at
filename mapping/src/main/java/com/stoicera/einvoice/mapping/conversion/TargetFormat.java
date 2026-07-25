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
 *
 * <p><strong>No wire identifier here.</strong> This enum used to carry an {@code id()} documented
 * as "the identifier this format is reported as in a {@link ConversionReport}" — which it never
 * was: the report's format strings come from the application layer's own {@code ConversionFormat},
 * whose spelling is the published query-string vocabulary ({@code ebinterface}, {@code ubl}). The
 * method had no caller anywhere in main or test code, and its Javadoc described a role a different
 * type was filling, which is worse than no method at all. Removed in the M4 hostile review (finding
 * F7); this enum's job is to select a loss profile, and it now says only that.
 */
public enum TargetFormat {

  /** ebInterface 6.1. */
  EBINTERFACE_61,

  /** UBL 2.1 under Peppol BIS Billing 3.0 — invoice or credit note, decided by BT-3. */
  UBL
}
