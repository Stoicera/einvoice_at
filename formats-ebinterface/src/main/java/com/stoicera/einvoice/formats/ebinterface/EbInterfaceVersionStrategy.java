package com.stoicera.einvoice.formats.ebinterface;

import com.stoicera.einvoice.formats.api.InvoiceFormatStrategy;

/**
 * Read/write strategy for one concrete ebInterface version.
 *
 * <p>SPEC §10: adding ebInterface 7.0 must not touch {@code core} — a new version is a new strategy
 * implementation. The contract itself (namespace, lenient reads, write) is the format-agnostic
 * {@link InvoiceFormatStrategy}; this sub-interface adds nothing but a name, because "which
 * ebInterface version" is a meaningful axis inside this module and a caller holding an {@code
 * EbInterfaceVersionStrategy} is saying something the raw supertype cannot.
 *
 * <p><strong>Scope of this seam (M2 → M4).</strong> M2 recorded honestly that the interface existed
 * without runtime polymorphism, there being exactly one implementation to be polymorphic over, and
 * deferred the real seam to M4 (ADR-0004 Entscheidung 10). M4 delivers it one level up: the shared
 * contract moved to {@code formats-api} so the UBL adapters implement the same one, and the
 * dispatch that picks between them is keyed on the format detected from the document's root
 * namespace ({@code validation}'s {@code DocumentFormat}), not on a registry of strategy objects.
 *
 * @param <T> the version-specific JAXB document type (e.g. {@code Ebi61InvoiceType})
 */
public interface EbInterfaceVersionStrategy<T> extends InvoiceFormatStrategy<T> {}
