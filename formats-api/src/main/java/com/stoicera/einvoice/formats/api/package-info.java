/**
 * The vocabulary every invoice-format adapter shares: {@link
 * com.stoicera.einvoice.formats.api.InvoiceFormatStrategy} (read/write one format+version) and
 * {@link com.stoicera.einvoice.formats.api.ReadResult} (the outcome of a lenient read).
 *
 * <p>This module has <strong>zero</strong> compile dependencies — not the JDK's XML stack beyond
 * {@code org.w3c.dom}, not a standards library, and deliberately not the canonical {@code core}
 * model. A format adapter is a standards-only adapter: canonical mapping happens in {@code
 * mapping}, never in a {@code formats-*} module, and that rule is what keeps this module out of
 * {@code core}. Enforced by {@code FormatsApiArchitectureTest}.
 */
package com.stoicera.einvoice.formats.api;
