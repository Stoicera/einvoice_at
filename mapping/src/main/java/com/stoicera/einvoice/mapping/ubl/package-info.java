/**
 * Canonical invoice ↔ UBL 2.1, customised to Peppol BIS Billing 3.0.
 *
 * <p>Main code depends on the ph-ubl JAXB types directly and <em>not</em> on the {@code
 * formats-ubl} adapter — the mapper builds a tree, the caller marshals it. That is the same split
 * the {@code ebinterface} sibling package uses, and it keeps the JAXB runtime out of this module's
 * compile scope.
 *
 * <p>Peppol conformance beyond the syntax is not this package's business: it emits the BIS
 * customisation and profile identifiers and maps every field EN 16931 defines, but whether a given
 * document satisfies the OpenPeppol Schematron is answered by the {@code validation} module against
 * the real, pinned rule set.
 */
package com.stoicera.einvoice.mapping.ubl;
