/**
 * Correction proposals with evidence ("Korrekturvorschlag mit Nachweis").
 *
 * <p>For a validator finding with a known, mechanical fix, {@link
 * com.stoicera.einvoice.validation.repair.RepairProposer} produces the corrected document, a
 * unified diff against the upload and an immediate re-validation of the corrected bytes by the same
 * {@link com.stoicera.einvoice.validation.InvoiceValidator}. Every change names the rule it
 * answers, the business term it fills and where the value came from: elsewhere in the same
 * document, the user's own input, or nowhere yet (a placeholder that is <em>not</em> written into
 * the document).
 *
 * <p>Two properties are the point of the package. Nothing is invented: a value is either copied
 * from the document, typed by the user, or reported as missing. And the rule set decides: the
 * proposal does not claim a fix works, it shows the re-validation result.
 *
 * <p>Plain Java, like the pipeline stages: no Spring, no AI, no I/O beyond the bytes passed in.
 */
package com.stoicera.einvoice.validation.repair;
