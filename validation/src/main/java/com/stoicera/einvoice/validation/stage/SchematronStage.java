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
 * SchematronResourcePure} is built once in a lazy holder and reused; the pure engine binds the
 * schema on first use and is safe to share across validation runs. Each failed assert becomes a
 * finding through {@link SchematronRuleCatalog}, which owns the bilingual message text; the SVRL
 * location is carried through verbatim.
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
   * surfacing as a mysterious runtime failure.
   */
  static boolean isCompiledSchematronValid() {
    return SchematronHolder.SCHEMATRON.isValidSchematron();
  }

  /**
   * Lazy, thread-safe holder for the compiled Schematron: the JVM initialises it on first access
   * and the class-init lock makes publication safe. Building it eagerly at class load would pay the
   * compilation cost even when no ebInterface document is ever validated.
   */
  private static final class SchematronHolder {

    private static final ISchematronResource SCHEMATRON =
        SchematronResourcePure.fromClassPath(SCHEMATRON_PATH);
  }
}
