package com.stoicera.einvoice.validation.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2: {@code validation} is a standards-and-rules module over phive. It must stay free of
 * Spring and JPA (the web/persistence layers wire it in, it never wires itself), and its main code
 * must not depend on the {@code mapping} module — validation checks a document against its own
 * format and profile; canonical mapping is a separate concern that depends on validation, never the
 * other way round. Test scope may use {@code mapping} once Task 9 adds round-trip fixtures. Never
 * weaken these rules to make a build pass; fix the design.
 */
class ValidationArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.validation");

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
  void mainCodeDoesNotDependOnTheMappingModule() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.mapping..")
        .check(MODULE_CLASSES);
  }
}
