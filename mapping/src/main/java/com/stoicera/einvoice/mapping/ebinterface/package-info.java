/**
 * Hand-written mapping from the canonical {@code core} {@code Invoice} to the ph-ebinterface 6.1
 * JAXB tree ({@code Ebi61InvoiceType}, module {@code formats-ebinterface}), via {@link
 * com.stoicera.einvoice.mapping.ebinterface.InvoiceToEbInterface61Mapper}.
 *
 * <p><strong>Boundary contract.</strong> This package performs <strong>no arithmetic</strong>: the
 * canonical model already derives and re-verifies every amount (ADR-0003, {@code
 * derive-don't-trust}), so mapping only copies those amounts into the target tree, never recomputes
 * them. It resolves exactly two national conventions that the ebInterface 6.1 XSD demands but core
 * does not: a party without a VAT id (permitted by core, e.g. Kleinunternehmer) marshals the
 * e-rechnung.gv.at {@code "ATU00000000"} placeholder rather than omitting the XSD-required {@code
 * VATIdentificationNumber}, and a country code marshals with the German display name as element
 * text alongside the ISO code on {@code @CountryCode}. The mapper is stateless, safe to share
 * across threads, and — like the rest of {@code mapping} — must not import Spring.
 */
package com.stoicera.einvoice.mapping.ebinterface;
