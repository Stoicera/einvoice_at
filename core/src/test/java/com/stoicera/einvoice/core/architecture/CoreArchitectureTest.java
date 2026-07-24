package com.stoicera.einvoice.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2: {@code core} depends on nothing but the JDK. This is the contract that makes the
 * canonical model reusable and fast to test; never weaken it to make a build pass.
 */
class CoreArchitectureTest {

  private static final JavaClasses CORE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.core");

  @Test
  void coreDependsOnNothingButTheJdk() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.core..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.core..", "java..")
        .check(CORE_CLASSES);
  }
}
