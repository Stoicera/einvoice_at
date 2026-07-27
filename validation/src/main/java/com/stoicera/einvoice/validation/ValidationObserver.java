package com.stoicera.einvoice.validation;

import java.util.function.Supplier;

/**
 * Wraps each step of the validation pipeline so a caller can time it, trace it or count it —
 * without this module depending on a tracing or metrics library.
 *
 * <p><strong>Why a port and not a Micrometer {@code Observation}.</strong> The same reason {@code
 * ai-assist} hands its token usage out over an {@code LlmUsageListener} port rather than
 * incrementing a counter itself: MILESTONES M6 asks for OpenTelemetry traces <em>across the
 * pipeline stages</em>, and SPEC §2 keeps every library module free of Spring. The stages live
 * here; the tracer lives in {@code app}. An interface is what lets both statements stay true.
 * {@code app} implements this in one small adapter over its {@code ObservationRegistry}; this
 * module stays plain Java and its own tests use a recording stub.
 *
 * <h2>Contract</h2>
 *
 * <p>An implementation <strong>must call {@code stage} exactly once and return its value</strong>.
 * It is a decorator around real work, not a listener: swallowing the call would skip a validation
 * stage, and calling it twice would run an XSLT transform twice. If the supplier throws, the
 * exception must propagate — the pipeline's stages do not throw on bad input by design ({@link
 * ValidationStage}), so an exception here is a defect that must not be hidden by the thing
 * measuring it.
 *
 * <p>This is deliberately <em>not</em> a {@code @FunctionalInterface}: {@link #observe} is generic,
 * and a generic method cannot be implemented by a lambda. {@link #NONE} is the do-nothing default
 * and the behaviour every existing caller keeps.
 */
public interface ValidationObserver {

  /** The XXE-hardened DOM parse of the raw upload — the first thing that touches hostile bytes. */
  String STAGE_PARSE = "parse";

  /** Root-namespace format detection ({@code FORMAT-01}/{@code FORMAT-02}). */
  String STAGE_FORMAT_DETECTION = "format-detection";

  /** ebInterface 6.1 XSD validation ({@code XSD-01}). */
  String STAGE_XSD = "xsd";

  /** This project's own AT-B2G Schematron ({@code AT-B2G-01/03/04/05}). */
  String STAGE_SCHEMATRON = "schematron";

  /** The hand-written AT-B2G business rules ({@code AT-B2G-02}). */
  String STAGE_BUSINESS_RULES = "business-rules";

  /** The official OpenPeppol rule set, run whole — the UBL pipeline's single stage. */
  String STAGE_PEPPOL = "peppol";

  /**
   * Runs one pipeline stage, returning exactly what it returned.
   *
   * @param stageName one of the {@code STAGE_*} constants above — a fixed, low-cardinality set, so
   *     it is safe to use directly as a span name or metric tag
   * @param stage the stage's work; must be invoked exactly once
   * @return the stage's own result
   * @param <T> whatever the stage produces — findings for most stages, the parsed DOM for {@link
   *     #STAGE_PARSE}
   */
  <T> T observe(String stageName, Supplier<T> stage);

  /** An observer that measures nothing — the default when no tracer is wired. */
  ValidationObserver NONE =
      new ValidationObserver() {
        @Override
        public <T> T observe(String stageName, Supplier<T> stage) {
          return stage.get();
        }
      };
}
