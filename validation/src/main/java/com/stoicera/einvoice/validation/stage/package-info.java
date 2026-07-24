/**
 * The four {@link com.stoicera.einvoice.validation.ValidationStage} implementations that make up
 * the validation pipeline: {@code FormatDetectionStage}, {@code XsdValidationStage}, {@code
 * SchematronStage} (backed by {@code SchematronRuleCatalog}) and {@code BusinessRuleStage}.
 *
 * <p><strong>Boundary contract.</strong> A stage is driven only by {@code
 * com.stoicera.einvoice.validation.EbInterface61Validator}, in the fixed order and with the hard
 * gating ADR-0004 documents — a stage may assume the preconditions earlier stages established (e.g.
 * {@code SchematronStage} and {@code BusinessRuleStage} run only once the document is already
 * XSD-valid) rather than re-checking them. No stage throws on malformed or non-compliant input:
 * every problem becomes a {@link com.stoicera.einvoice.core.validation.Finding}, German-first.
 * Foreign, document-influenced text (Xerces diagnostics, SVRL assert text) is bounded via {@link
 * com.stoicera.einvoice.validation.internal.BoundedText} before it reaches a finding, so a hostile
 * document value cannot overflow a {@code Finding}'s length invariants and throw.
 */
package com.stoicera.einvoice.validation.stage;
