package com.stoicera.einvoice.rendering.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2: {@code rendering} turns the canonical model into a print view. It is a plain-Java module
 * — no Spring, no JPA — and it must not reach into a format adapter or the mapping module: a print
 * view is of the invoice, not of an ebInterface or UBL tree, and letting it read one would be the
 * first step towards two renderers that disagree. Never weaken these rules to make a build pass;
 * fix the design instead.
 */
class RenderingArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.rendering");

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
  void doesNotDependOnAFormatAdapterOrOnMapping() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.formats..",
            "com.stoicera.einvoice.mapping..",
            "com.stoicera.einvoice.validation..")
        .because("a print view renders the canonical invoice, never a format-specific tree")
        .check(MODULE_CLASSES);
  }

  @Test
  void dependsOnlyOnAllowedModules() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.rendering..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.rendering..",
            "com.stoicera.einvoice.core..",
            "org.apache.pdfbox..",
            "java..")
        .check(MODULE_CLASSES);
  }
}
