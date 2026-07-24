package com.stoicera.einvoice.mapping.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2: {@code mapping} translates between the canonical {@code core} model and the standards
 * adapters. It is a plain-Java module — no Spring, no JPA — and may depend only on {@code core},
 * the ebInterface adapter ({@code formats-ebinterface} + ph-ebinterface), Jackson (Task 5) and the
 * JDK. Never weaken these rules to make a build pass; fix the design instead.
 */
class MappingArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.mapping");

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
  void dependsOnlyOnAllowedModules() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.mapping..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.mapping..",
            "com.stoicera.einvoice.core..",
            "com.stoicera.einvoice.formats.ebinterface..",
            "com.helger..",
            "com.fasterxml.jackson..",
            "java..")
        .check(MODULE_CLASSES);
  }
}
