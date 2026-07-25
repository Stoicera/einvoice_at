package com.stoicera.einvoice.validation.stage;

import com.helger.schematron.ISchematronResource;
import com.helger.schematron.pure.SchematronResourcePure;
import com.helger.schematron.svrl.SVRLFailedAssert;
import com.helger.schematron.svrl.SVRLHelper;
import com.helger.schematron.svrl.jaxb.SchematronOutputType;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.ValidationStage;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;

/**
 * Validates the DOM against our own AT-B2G Schematron ({@code at-b2g-ebinterface-6.1.sch}) via
 * ph-schematron's pure (XPath) engine.
 *
 * <p>ebInterface ships no official Schematron and phive's ebInterface VES is XSD-only, so these
 * rules are original work: business constraints a federal recipient requires that the structural
 * schema cannot express. The facade runs this stage only after the XSD stage produced zero findings
 * — a structurally invalid tree cannot be meaningfully checked by XPath rules — so this stage
 * always sees a well-formed, schema-valid 6.1 document.
 *
 * <p>Compiling (parsing and binding) the Schematron is expensive, so the {@link
 * SchematronResourcePure} is compiled once in a static holder and reused across every validation
 * run. The pure engine's own bind is a lazy, unsynchronised write of a non-volatile field, which
 * would race under concurrent first use (M3's single shared validator over HTTP request threads);
 * the holder therefore forces the bind eagerly at initialisation so the JVM class-init lock
 * publishes a fully-bound, thereafter read-only resource that is genuinely safe to share — see
 * {@link SchematronHolder}. Each failed assert becomes a finding through {@link
 * SchematronRuleCatalog}, which owns the bilingual message text; the SVRL location is carried
 * through, bounded to a safe length (512 chars, {@code BoundedText.MAX_LOCATION}).
 */
public final class SchematronStage implements ValidationStage {

  /** Classpath location of the bundled AT-B2G Schematron. */
  static final String SCHEMATRON_PATH = "/schematron/at-b2g-ebinterface-6.1.sch";

  @Override
  public List<Finding> apply(ValidationContext ctx) {
    Document dom = ctx.dom().orElseThrow(); // the facade runs this stage only with a parsed DOM

    SchematronOutputType svrl = validate(dom);

    List<Finding> findings = new ArrayList<>();
    for (SVRLFailedAssert failedAssert : SVRLHelper.getAllFailedAssertions(svrl)) {
      findings.add(SchematronRuleCatalog.toFinding(failedAssert));
    }
    return findings;
  }

  private static SchematronOutputType validate(Document dom) {
    try {
      return SchematronHolder.SCHEMATRON.applySchematronValidationToSVRL(dom, null);
    } catch (Exception e) {
      // The bundled Schematron is valid and the DOM is already parsed and XSD-clean, so a failure
      // here is a defect in the engine or the packaged rules, not untrusted input. Surface it
      // loudly rather than let it masquerade as "no findings".
      throw new IllegalStateException("AT-B2G Schematron evaluation failed unexpectedly", e);
    }
  }

  /**
   * Whether the bundled AT-B2G Schematron parses and binds cleanly. Exercised by the stage test so
   * a malformed {@code .sch} (or a broken classpath) fails a fast, obvious assertion rather than
   * surfacing as a mysterious runtime failure. The result is computed once, when the holder binds
   * the schema (see {@link SchematronHolder}), so reading it never triggers a second compile.
   */
  static boolean isCompiledSchematronValid() {
    return SchematronHolder.SCHEMATRON_VALID;
  }

  /**
   * Holder for the compiled Schematron, initialised lazily on first access to this class (so the
   * compilation cost is not paid when no ebInterface document is ever validated) but bound eagerly
   * once it is.
   *
   * <p>{@link SchematronResourcePure#fromClassPath} only <em>constructs</em> the resource; the pure
   * engine compiles ("binds") the schema lazily, on first {@code applySchematronValidationToSVRL},
   * through {@code getOrCreateBoundSchema()} — verified in the pinned {@code
   * ph-schematron-pure-10.0.0} bytecode to be an unsynchronised {@code getfield / ifnonnull /
   * createBoundSchema / putfield} write of the <em>non-volatile</em> instance field {@code
   * m_aBoundSchema}. Two threads racing that first bind would each run the expensive compile and
   * could unsafely publish a half-built bound schema to the losing thread.
   *
   * <p>Calling {@link ISchematronResource#isValidSchematron()} in this initializer forces that bind
   * (the method calls {@code getOrCreateBoundSchema()} itself) exactly once, under the JVM's
   * class-initialisation lock. That lock establishes a happens-before to every later reader of
   * {@code SCHEMATRON}, so once initialisation completes the resource is fully bound and every
   * subsequent {@code applySchematronValidationToSVRL} only ever takes the already-bound read path
   * — no thread re-enters the write. This is what makes the stage's "safe to share" claim true; a
   * concurrency regression test ({@code InvoiceValidatorConcurrencyTest}) pins it.
   */
  private static final class SchematronHolder {

    private static final ISchematronResource SCHEMATRON =
        SchematronResourcePure.fromClassPath(SCHEMATRON_PATH);

    /**
     * Validity of the bundled Schematron, captured once. Evaluating {@link
     * ISchematronResource#isValidSchematron()} here also forces the otherwise-lazy compile, so the
     * class-init lock covers the bind and publishes a fully-bound resource (see the class Javadoc).
     */
    private static final boolean SCHEMATRON_VALID = SCHEMATRON.isValidSchematron();
  }
}
