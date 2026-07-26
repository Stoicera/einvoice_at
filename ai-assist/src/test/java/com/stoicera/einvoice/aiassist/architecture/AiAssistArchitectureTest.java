package com.stoicera.einvoice.aiassist.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * SPEC §2: {@code ai-assist} is a plain-Java module called only from {@code app}, behind the {@code
 * features.ai-explanations} flag. Never weaken these rules to make a build pass; fix the design
 * instead (CLAUDE.md).
 *
 * <p>The provider-isolation rule is the one with teeth: everything above the {@link
 * com.stoicera.einvoice.aiassist.llm.LlmClient} port must be blind to which provider is in use,
 * which is what makes ENGINEERING_STANDARDS §8's "austauschbar" claim true rather than
 * aspirational.
 */
class AiAssistArchitectureTest {

  private static final JavaClasses MODULE_CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice.aiassist");

  @Test
  void moduleClassesAreImported() {
    // A mistyped package string would import zero classes and make every rule below pass vacuously
    // (finding A8, applied here from the start rather than after a review notices).
    assertThat(MODULE_CLASSES).isNotEmpty();
    assertThat(inPackage("com.stoicera.einvoice.aiassist.llm..")).isPositive();
    assertThat(inPackage("com.stoicera.einvoice.aiassist.openrouter..")).isPositive();
    assertThat(inPackage("com.stoicera.einvoice.aiassist.internal..")).isPositive();
  }

  @Test
  void doesNotDependOnSpring() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..")
        .because("app owns the wiring; this module is plain Java (SPEC §2)")
        .check(MODULE_CLASSES);
  }

  @Test
  void doesNotDependOnJpa() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.persistence..")
        .because("only app knows the database (SPEC §2)")
        .check(MODULE_CLASSES);
  }

  @Test
  void doesNotDependOnFormatsMappingOrValidation() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.formats..",
            "com.stoicera.einvoice.mapping..",
            "com.stoicera.einvoice.validation..",
            "com.stoicera.einvoice.rendering..")
        .because(
            "an explanation is produced from a core Finding, not from a format tree or a validation"
                + " pipeline — reaching into validation would couple the AI feature to how findings"
                + " are produced rather than to what they are")
        .check(MODULE_CLASSES);
  }

  @Test
  void onlyTheAdapterKnowsWhichProviderIsUsed() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.stoicera.einvoice.aiassist.openrouter..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.aiassist.openrouter..")
        .because(
            "ENGINEERING_STANDARDS §8: provider access goes through the LlmClient port only, so a"
                + " second provider is a sibling package and nothing above the port changes")
        .check(MODULE_CLASSES);
  }

  @Test
  void theLlmPortKnowsNothingAboutInvoicesOrFindings() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.aiassist.llm..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.aiassist.llm..", "java..")
        .because(
            "the port is the narrow abstraction, not a domain type: if it learned what a Finding is,"
                + " it would stop being reusable for the next thing this platform asks a model")
        .check(MODULE_CLASSES);
  }

  @Test
  void dependsOnlyOnAllowedLibraries() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.aiassist..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "com.stoicera.einvoice.aiassist..",
            "com.stoicera.einvoice.core..",
            "com.fasterxml.jackson..",
            "org.slf4j..",
            "java..")
        .because(
            "the HTTP client is the JDK's own; there is deliberately no client library, no metrics"
                + " library (see LlmUsageListener) and no template engine (see PromptTemplate)")
        .check(MODULE_CLASSES);
  }

  private static long inPackage(String pattern) {
    return MODULE_CLASSES.stream().filter(JavaClass.Predicates.resideInAPackage(pattern)).count();
  }
}
