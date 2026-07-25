package com.stoicera.einvoice.formats.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2: {@code formats-api} is the format adapters' shared vocabulary and nothing else. It must
 * stay implementable by any adapter, which means depending on no standards library, no framework,
 * and — the load-bearing one — not on the canonical {@code core} model: a {@code formats-*} module
 * is a standards-only adapter, and canonical mapping happens in {@code mapping}. That rule is
 * exactly why {@code ReadResult} lives here rather than in {@code core}. Never weaken these rules
 * to make a build pass; fix the design.
 */
class FormatsApiArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.formats.api");

  @Test
  void moduleClassesAreImported() {
    // A mistyped package string would import zero classes and make each rule below pass vacuously;
    // asserting a non-empty import makes that failure loud (finding A8).
    assertThat(MODULE_CLASSES).isNotEmpty();
  }

  @Test
  void dependsOnNothingButTheJdk() {
    // org.w3c.dom is JDK (module java.xml), not a third-party XML stack — InvoiceFormatStrategy's
    // DOM overload is what lets the validation module hand an adapter its already-hardened parse
    // instead of the raw untrusted bytes.
    classes()
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.formats.api..", "java..", "org.w3c.dom..")
        .because(
            "formats-api must stay implementable by any adapter, so it carries no dependencies")
        .check(MODULE_CLASSES);
  }

  @Test
  void doesNotDependOnTheCanonicalCoreModel() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.core..")
        .because(
            "a formats-* module is a standards-only adapter; canonical mapping lives in mapping")
        .check(MODULE_CLASSES);
  }
}
