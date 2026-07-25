package com.stoicera.einvoice.formats.ubl.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2/§10: {@code formats-ubl} is a standards-only adapter over ph-ubl. It must not depend on
 * Spring, JPA, or the canonical {@code core} model — canonical mapping happens in the {@code
 * mapping} module, never here — and it must not reach sideways into the ebInterface adapter, which
 * would couple two independent format adapters to each other. Never weaken these rules to make a
 * build pass; fix the design.
 */
class FormatsUblArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.formats.ubl");

  @Test
  void moduleClassesAreImported() {
    // A mistyped package string would import zero classes and make each rule below pass vacuously;
    // asserting a non-empty import makes that failure loud (finding A8).
    assertThat(MODULE_CLASSES).isNotEmpty();
  }

  @Test
  void doesNotDependOnSpring() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..")
        .check(MODULE_CLASSES);
  }

  @Test
  void doesNotDependOnJpa() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.persistence..")
        .check(MODULE_CLASSES);
  }

  @Test
  void doesNotDependOnTheCanonicalCoreModel() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.core..")
        .check(MODULE_CLASSES);
  }

  @Test
  void doesNotDependOnTheEbInterfaceAdapter() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.formats.ebinterface..")
        .because(
            "the two format adapters are siblings, not layers; anything they genuinely share"
                + " belongs in formats-api")
        .check(MODULE_CLASSES);
  }

  @Test
  void dependsOnlyOnAllowedModules() {
    // Positive whitelist, the same shape mapping and validation carry: a new cross-module edge has
    // to be admitted here on purpose rather than arriving as an undeclared transitive.
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.formats.ubl..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.formats.ubl..",
            "com.stoicera.einvoice.formats.api..",
            "com.helger..",
            "oasis.names.specification.ubl..",
            "java..",
            "javax.xml..",
            "org.w3c.dom..")
        .check(MODULE_CLASSES);
  }
}
