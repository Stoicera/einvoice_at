package com.stoicera.einvoice.validation.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

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
 *
 * <p>Beyond the negative rules, a positive whitelist ({@link #dependsOnlyOnAllowedModules()}) pins
 * the exact set of packages main code may reach, so an accidental dependency on a sibling module
 * ({@code rendering}, {@code ai-assist}) or a raw parser internal fails loudly rather than sliding
 * in unnoticed — the same shape {@code mapping} already carries (finding A8). It earned its keep in
 * M4: extracting {@code ReadResult} into the new {@code formats-api} module silently gave this
 * module a fresh cross-module edge (through {@code ValidationContext}'s call to {@code
 * ReadResult.document()}), and this rule is what surfaced it, so the edge could be declared in the
 * POM and admitted here on purpose rather than arriving as an undeclared transitive.
 */
class ValidationArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.validation");

  @Test
  void moduleClassesAreImported() {
    // Guards every rule below against passing vacuously: if the package string above is ever
    // mistyped, the import comes back empty and each noClasses()/classes() rule would trivially
    // hold. Asserting a non-empty import makes that failure loud (finding A8).
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
  void mainCodeDoesNotDependOnTheMappingModule() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.mapping..")
        .check(MODULE_CLASSES);
  }

  @Test
  void dependsOnlyOnAllowedModules() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.validation..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.validation..",
            "com.stoicera.einvoice.core..",
            "com.stoicera.einvoice.formats.api..",
            "com.stoicera.einvoice.formats.ebinterface..",
            "com.stoicera.einvoice.formats.ubl..",
            "com.helger..",
            "java..",
            "javax.xml..",
            "org.w3c.dom..",
            "org.xml.sax..")
        .check(MODULE_CLASSES);
  }
}
