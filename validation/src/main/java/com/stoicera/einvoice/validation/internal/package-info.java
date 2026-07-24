/**
 * Internal helpers not part of this module's public surface: {@code SecureXml} (XXE-hardened DOM
 * parsing at the system boundary, Engineering Standards §4) and {@code BoundedText} (capping
 * foreign, document-influenced text before it reaches a {@link
 * com.stoicera.einvoice.core.validation.Finding}, so an over-long value cannot overflow {@code
 * Finding}'s own length invariants and break the pipeline's never-throws contract).
 *
 * <p><strong>Boundary contract.</strong> This package is what every stage in {@code
 * com.stoicera.einvoice.validation.stage} builds on but nothing outside the {@code validation}
 * module should reference directly (Java's {@code internal} sub-package is the convention signal
 * here, not an enforced module boundary — {@code core}'s own {@code internal} package establishes
 * the same pattern). {@code SecureXml} is the single place raw untrusted bytes are ever parsed in
 * this module; every downstream stage works from the DOM it produces.
 */
package com.stoicera.einvoice.validation.internal;
