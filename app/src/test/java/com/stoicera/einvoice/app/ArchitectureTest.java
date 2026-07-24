package com.stoicera.einvoice.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.app.security.TenantProvisioningService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/**
 * SPEC §2's first cross-module rule set: "only {@code app} knows the database". Every lib module
 * (core, mapping, validation, formats-ebinterface) already asserts, from its own, narrower vantage
 * point, that it stays free of Spring/JPA and never depends on {@code app} (it cannot — {@code app}
 * is not on its compile classpath). What only {@code app}'s test classpath can see is the union of
 * all of them together with {@code app} itself, so this is the first place a rule can genuinely
 * span every module in the tree. Never weaken a rule here to make a build pass; fix the design
 * instead (CLAUDE.md).
 *
 * <p>Four rules:
 *
 * <ol>
 *   <li>{@link #libModulesNeverDependOnApp()} — a lib module cannot see {@code app} (belt over the
 *       fact that it is not even on the compile classpath: this additionally forbids a stray
 *       test-scope dependency from creating the edge).
 *   <li>{@link #persistenceTechnologyStaysInAppPersistence()} — "only {@code app} knows the
 *       database", sharpened to package level: {@code jakarta.persistence..}, {@code
 *       org.springframework.data..} and {@code org.flywaydb..} are JPA/Spring-Data/Flyway
 *       themselves, and {@code org.springframework.dao..} is the exception hierarchy Spring Data
 *       throws on constraint violations — all four are database technology, and only {@code
 *       ..app.persistence..} (entities/repositories) may depend on them directly. Three services
 *       outside that package have a narrow, named reason to reach one of these packages anyway (see
 *       {@link #PERSISTENCE_TECH_EXCEPTIONS}); every other class is held to the rule with no
 *       blanket exemption.
 *   <li>{@link #controllersDoNotDependOnRepositoriesDirectly()} — {@code ..app.api..} controllers
 *       reach persistence only through a service or security component, never a Spring Data
 *       repository directly. {@code InvoiceController}/{@code ReportController} already followed
 *       this shape (T6/T7: {@code InvoiceService}/{@code ReportService} own their repositories);
 *       {@code ApiKeyController} did not — it held {@code ApiKeyRepository} directly. Task 10
 *       extracted {@code ApiKeyService} (in {@code ..app.security..}, alongside {@code
 *       CurrentTenant}/{@code ApiKeyAuthFilter}, which already held repositories directly as
 *       security-layer components — this rule is about controllers, not about "app.security may
 *       never touch persistence") so the rule holds without weakening. Hardened in the Task 10 fix
 *       wave from a name-based predicate ({@code ..app.persistence..} package + simple name ending
 *       {@code Repository}) to a type-based one (assignable to {@code
 *       org.springframework.data.repository.Repository}), which also catches a {@code
 *       *RepositoryImpl} fragment or a renamed repository interface that the old, name-based
 *       predicate would have missed; the name-based clause is kept OR'd in as belt-and-suspenders,
 *       but the assignable-to predicate is the load-bearing part. Note the rule does not forbid
 *       controllers from depending on {@code ..app.persistence..} <em>entities</em> (e.g. {@code
 *       TenantEntity}, returned by {@code CurrentTenant.require}) — that is the shape T6/T7
 *       actually built, not a layering this task invents.
 *   <li>{@link #coreStaysJdkOnlyAcrossTheWholeClasspath()} — {@code CoreArchitectureTest} already
 *       asserts this from {@code core}'s own, narrower import; this is the same assertion evaluated
 *       against the full cross-module import, cheap insurance against a future dependency reaching
 *       {@code core} from a direction its own test cannot see.
 * </ol>
 */
class ArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.stoicera.einvoice");

  /**
   * Classes allowed to depend on {@code jakarta.persistence..}, {@code org.springframework.data..}
   * , {@code org.springframework.dao..} or {@code org.flywaydb..} outside {@code
   * ..app.persistence..}. Each entry was found by grepping {@code app}'s main sources for those
   * four package prefixes and inspecting the one real usage in each hit (never a blanket
   * package/layer exemption):
   *
   * <ul>
   *   <li>{@link InvoiceService} — {@code org.springframework.data.domain.Page/PageRequest/Sort} to
   *       build the paginated invoice listing, and {@code
   *       org.springframework.dao.DataIntegrityViolationException} to translate the {@code (tenant,
   *       invoiceNumber)} unique-index violation into {@link
   *       com.stoicera.einvoice.app.invoice.DuplicateInvoiceException}.
   *   <li>{@link ReportService} — the same {@code Page/PageRequest/Sort} triad for the paginated
   *       report listing.
   *   <li>{@link TenantProvisioningService} — {@code DataIntegrityViolationException} to detect and
   *       recover from the concurrent-first-request tenant-provisioning race.
   * </ul>
   */
  private static final DescribedPredicate<JavaClass> PERSISTENCE_TECH_EXCEPTIONS =
      whitelisted(InvoiceService.class, ReportService.class, TenantProvisioningService.class);

  @Test
  void moduleClassesAreImported() {
    // A mistyped package string, or a lib module accidentally missing from app's test classpath
    // (e.g. a dependency dropped from app/pom.xml), would make one or more rules below pass
    // vacuously; assert every module segment the rules below scope to actually contributed classes
    // to the import (finding A8, applied cross-module).
    assertThat(CLASSES).isNotEmpty();
    assertThat(countInPackage(CLASSES, "com.stoicera.einvoice.core..")).isPositive();
    assertThat(countInPackage(CLASSES, "com.stoicera.einvoice.mapping..")).isPositive();
    assertThat(countInPackage(CLASSES, "com.stoicera.einvoice.validation..")).isPositive();
    assertThat(countInPackage(CLASSES, "com.stoicera.einvoice.formats.ebinterface..")).isPositive();
    assertThat(countInPackage(CLASSES, "com.stoicera.einvoice.app..")).isPositive();
    assertThat(countInPackage(CLASSES, "com.stoicera.einvoice.app.api..")).isPositive();
    assertThat(countInPackage(CLASSES, "com.stoicera.einvoice.app.persistence..")).isPositive();
  }

  @Test
  void libModulesNeverDependOnApp() {
    noClasses()
        .that()
        .resideInAnyPackage(
            "com.stoicera.einvoice.core..",
            "com.stoicera.einvoice.mapping..",
            "com.stoicera.einvoice.validation..",
            "com.stoicera.einvoice.formats.ebinterface..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.app..")
        .because("SPEC §2: the app composes the libs, never the other way round")
        .check(CLASSES);
  }

  @Test
  void persistenceTechnologyStaysInAppPersistence() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.stoicera.einvoice.app.persistence..")
        .and(DescribedPredicate.not(PERSISTENCE_TECH_EXCEPTIONS))
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "jakarta.persistence..",
            "org.springframework.data..",
            "org.springframework.dao..",
            "org.flywaydb..")
        .because(
            "SPEC §2: only app.persistence (entities/repositories) knows the database technology;"
                + " see PERSISTENCE_TECH_EXCEPTIONS for the narrow, named exceptions")
        .check(CLASSES);
  }

  @Test
  void controllersDoNotDependOnRepositoriesDirectly() {
    noClasses()
        .that()
        .resideInAPackage("com.stoicera.einvoice.app.api..")
        .should()
        .dependOnClassesThat(
            // Type-based and load-bearing: catches any Spring Data repository interface — including
            // a *RepositoryImpl fragment or a renamed repository that would not end in "Repository"
            // — by what it structurally is (assignable to the Spring Data marker interface), not by
            // what it happens to be called. The package/name clause is kept only as an OR'd
            // belt-and-suspenders on top, never the sole guard.
            JavaClass.Predicates.assignableTo(Repository.class)
                .or(
                    JavaClass.Predicates.resideInAPackage("com.stoicera.einvoice.app.persistence..")
                        .and(JavaClass.Predicates.simpleNameEndingWith("Repository"))))
        .because(
            "controllers reach persistence only through a service or security component"
                + " (InvoiceService/ReportService/ApiKeyService), never a Spring Data repository"
                + " directly (type-based: assignable to org.springframework.data.repository.Repository)")
        .check(CLASSES);
  }

  @Test
  void httpStatusDecisionsStayOutOfServices() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.stoicera.einvoice.app.api..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.web.server.ResponseStatusException")
        .because(
            "ResponseStatusException exists only to let non-web code smuggle an HTTP status out of"
                + " a service; ApiKeyService did exactly that, which also left its 404 speaking the"
                + " framework's generic not-found type instead of a per-condition slug (ADR-0006)."
                + " Services throw domain exceptions (InvoiceNotFoundException,"
                + " ReportNotFoundException, ApiKeyNotFoundException, TooManyApiKeysException) and"
                + " ApiExceptionHandler owns the status. Filters writing their own problem response"
                + " are unaffected: they use org.springframework.http.HttpStatus, not this class")
        .check(CLASSES);
  }

  @Test
  void coreStaysJdkOnlyAcrossTheWholeClasspath() {
    classes()
        .that()
        .resideInAPackage("com.stoicera.einvoice.core..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("com.stoicera.einvoice.core..", "java..")
        .because("belt over CoreArchitectureTest's module-local assertion of the same rule")
        .check(CLASSES);
  }

  private static long countInPackage(JavaClasses classes, String packagePattern) {
    return classes.stream().filter(JavaClass.Predicates.resideInAPackage(packagePattern)).count();
  }

  private static DescribedPredicate<JavaClass> whitelisted(Class<?>... exceptions) {
    Set<String> names = Arrays.stream(exceptions).map(Class::getName).collect(Collectors.toSet());
    return DescribedPredicate.describe(
        "is one of the explicitly whitelisted classes " + names,
        javaClass -> names.contains(javaClass.getName()));
  }
}
