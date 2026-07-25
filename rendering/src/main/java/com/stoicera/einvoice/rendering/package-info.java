/**
 * Canonical invoice → PDF print view.
 *
 * <p>Renders the canonical {@code core} model, never a format-specific tree: an ebInterface
 * document and the UBL document converted from it are the same invoice, so they must print
 * identically, and going through {@code core} is what makes that true rather than hoping two
 * renderers agree.
 *
 * <p>Must not import Spring, and depends on nothing but {@code core} and Apache PDFBox. Enforced by
 * {@code RenderingArchitectureTest}.
 */
package com.stoicera.einvoice.rendering;
