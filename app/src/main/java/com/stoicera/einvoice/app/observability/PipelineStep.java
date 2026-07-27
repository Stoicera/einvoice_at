package com.stoicera.einvoice.app.observability;

/**
 * The steps of the invoice pipeline that {@code app} itself orchestrates, and the only values that
 * ever appear in the {@code step} tag of the {@code einvoice.pipeline} observation.
 *
 * <p><strong>Why an enum and not a string.</strong> A metric tag whose values come from anywhere
 * but a fixed, compile-time set is an unbounded number of time series — the exact defect the M5
 * hostile review found in the AI cost metrics, where the {@code model} tag was read out of a third
 * party's response body while the base URL could point anywhere. Nothing here is caller-supplied,
 * and an enum is what keeps it that way when someone adds a step in a hurry.
 *
 * <p>The validation pipeline's own stages are <em>not</em> in this list. They live one module down,
 * behind {@link com.stoicera.einvoice.validation.ValidationObserver}, and reach Micrometer through
 * {@link MicrometerValidationObserver} — see that class for why the boundary is where it is.
 */
public enum PipelineStep {

  /** Canonical JSON → {@code Invoice}: the domain model's own invariants run here. */
  READ_CANONICAL_JSON("read-canonical-json"),

  /** {@code Invoice} → ebInterface 6.1 object tree. */
  MAP_EBINTERFACE("map-ebinterface"),

  /** ebInterface 6.1 object tree → XML. */
  WRITE_EBINTERFACE("write-ebinterface"),

  /** ebInterface 6.1 XML → {@code Invoice} (the reverse direction, used by conversion). */
  READ_EBINTERFACE("read-ebinterface"),

  /** {@code Invoice} → Peppol BIS Billing 3.0 UBL object tree. */
  MAP_UBL("map-ubl"),

  /** UBL object tree → XML. */
  WRITE_UBL("write-ubl"),

  /** UBL XML → {@code Invoice} (the reverse direction, used by conversion). */
  READ_UBL("read-ubl"),

  /** {@code Invoice} → the German A4 PDF print view. */
  RENDER_PDF("render-pdf"),

  /** The invoice row, its report row and the audit event, committed together. */
  PERSIST_INVOICE("persist-invoice"),

  /** One report's findings sent to the LLM provider and explained (feature-flagged). */
  EXPLAIN_FINDINGS("explain-findings");

  private final String tag;

  PipelineStep(String tag) {
    this.tag = tag;
  }

  /** The value this step contributes to the {@code step} tag / span name. */
  public String tag() {
    return tag;
  }
}
