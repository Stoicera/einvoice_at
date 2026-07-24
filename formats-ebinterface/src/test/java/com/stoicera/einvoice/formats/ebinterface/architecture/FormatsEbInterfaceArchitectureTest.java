package com.stoicera.einvoice.formats.ebinterface.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2/§10: {@code formats-ebinterface} is a standards-only adapter over ph-ebinterface. It must
 * not depend on Spring, JPA, or the canonical {@code core} model — canonical mapping happens in the
 * {@code mapping} module, never here. Never weaken these rules to make a build pass; fix the
 * design.
 */
class FormatsEbInterfaceArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.formats.ebinterface");

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
}
