package com.stoicera.einvoice.mapping.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2: {@code mapping} translates between the canonical {@code core} model and the standards
 * adapters. It is a plain-Java module — no Spring, no JPA — and its main code may depend only on
 * {@code core}, the ph-ebinterface JAXB types it maps into, Jackson (the JSON boundary) and the
 * JDK. The {@code formats-ebinterface} adapter is a test-scope dependency (used only to marshal
 * mapped trees back for the schema-validity property), so it is not on the main whitelist (finding
 * A9). Never weaken these rules to make a build pass; fix the design instead.
 */
class MappingArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.mapping");

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
  void dependsOnlyOnAllowedModules() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.mapping..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.mapping..",
            "com.stoicera.einvoice.core..",
            "com.helger..",
            "com.fasterxml.jackson..",
            "java..")
        .check(MODULE_CLASSES);
  }
}
