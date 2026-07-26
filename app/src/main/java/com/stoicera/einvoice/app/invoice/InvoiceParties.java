package com.stoicera.einvoice.app.invoice;

/**
 * The two party names of a stored invoice — nothing else.
 *
 * <p>Exists for one caller and one purpose: {@code ReportExplanationService} needs the seller and
 * buyer names of the invoice a report belongs to, so it can hand them to {@code
 * ExplanationContext.withPartyNames} as literals to redact before any text reaches the LLM.
 * Returning a whole {@code Invoice} (or its canonical JSON) for that would hand a caller the entire
 * document when it asked for two strings, and the point of the call is to <em>reduce</em> what
 * travels.
 *
 * <p>{@code buyerName} is nullable: the canonical model does not require it.
 *
 * @param sellerName BT-27, the seller's registered name
 * @param buyerName BT-44, the buyer's name, or {@code null}
 */
public record InvoiceParties(String sellerName, String buyerName) {}
