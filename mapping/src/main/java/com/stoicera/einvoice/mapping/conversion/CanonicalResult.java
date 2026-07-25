package com.stoicera.einvoice.mapping.conversion;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.validation.Finding;
import java.util.List;

/**
 * The outcome of reading a format document back into the canonical model: the invoice, plus every
 * note the read produced.
 *
 * <p>A reverse mapper cannot simply return an {@link Invoice}. The canonical model <em>derives</em>
 * its VAT breakdown and totals from the lines and re-verifies them (ADR-0003), so reading a
 * document whose stated totals disagree with what the lines imply is not a failure to be thrown and
 * not a discrepancy to be swallowed — it is information the caller needs. Likewise a source
 * document routinely carries fields the canonical model has no place for. Both arrive here as
 * notes.
 *
 * @param invoice the canonical invoice, never {@code null}
 * @param notes losses, convention translations and deviations found while reading, possibly empty
 */
public record CanonicalResult(Invoice invoice, List<Finding> notes) {

  public CanonicalResult {
    notes = List.copyOf(notes);
  }
}
