/**
 * ebInterface 6.1 read/write, wrapping ph-ebinterface behind a version-strategy interface.
 *
 * <p>{@link com.stoicera.einvoice.formats.ebinterface.EbInterfaceVersionStrategy} is the seam that
 * lets ebInterface 7.0 (announced for Q4 2026) be added as a new implementation without touching
 * {@code core} (SPEC §10). {@link com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy}
 * is the current implementation.
 *
 * <p><strong>Boundary contract.</strong> This module is a thin, standards-only JAXB adapter:
 *
 * <ul>
 *   <li>It reads and writes the ph-ebinterface JAXB types (e.g. {@code Ebi61InvoiceType}); it does
 *       not know about the canonical {@code core} model. Mapping to/from {@code core} lives in the
 *       {@code mapping} module, enforced by ArchUnit.
 *   <li>Reads are deliberately <em>lenient</em>: the underlying marshaller's XSD schema validation
 *       is switched off. Well-formedness and JAXB structural errors fail a read (with the
 *       diagnostics collected, not thrown); XSD and Schematron validation are the {@code
 *       validation} module's job, never this one.
 *   <li>It must not depend on Spring or JPA.
 * </ul>
 */
package com.stoicera.einvoice.formats.ebinterface;
