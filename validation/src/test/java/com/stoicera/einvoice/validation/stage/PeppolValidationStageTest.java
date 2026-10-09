package com.stoicera.einvoice.validation.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helger.diagnostics.error.SingleError;
import com.helger.diagnostics.error.level.EErrorLevel;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.DocumentFormat;
import com.stoicera.einvoice.validation.RuleIds;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.internal.BoundedText;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit-level behaviour of {@link PeppolValidationStage}. The stage's real work — actually judging a
 * document against the OpenPeppol rule set — is proven end to end by {@code PeppolRoundTripTest}
 * and the golden-file corpus; what is pinned here is the wiring around it: the version pin, the
 * dispatch, and the translation of one foreign diagnostic into a {@code Finding}.
 */
class PeppolValidationStageTest {

  private final PeppolValidationStage stage = new PeppolValidationStage();

  /**
   * The pin must resolve. A phive-rules upgrade that drops {@link
   * PeppolValidationStage#RULE_SET_VERSION} would otherwise surface as a runtime failure on the
   * first upload rather than as a failing build.
   */
  @Test
  void thePinnedRuleSetIsRegistered() {
    assertThat(PeppolValidationStage.isPinnedRuleSetRegistered()).isTrue();
    assertThat(PeppolValidationStage.RULE_SET_VERSION).isEqualTo("2026.5");
  }

  @Test
  void refusesToValidateANonUblDocument() {
    ValidationContext ctx =
        new ValidationContext(
            ("<?xml version=\"1.0\"?><Invoice xmlns=\"http://www.ebinterface.at/schema/6p1/\"/>")
                .getBytes(StandardCharsets.UTF_8));
    ctx.format(DocumentFormat.EBINTERFACE_61);

    assertThatThrownBy(() -> stage.apply(ctx))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UBL");
  }

  /** A rule's own identifier is what the finding is keyed on — never replaced by a local code. */
  @Test
  void usesTheSchematronAssertionIdAsTheRuleId() {
    Finding finding =
        PeppolValidationStage.toFinding(
            error(
                EErrorLevel.ERROR,
                "PEPPOL-EN16931-R020",
                "Seller electronic address MUST be provided"));

    assertThat(finding.ruleId()).isEqualTo("PEPPOL-EN16931-R020");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    // The official English wording is preserved verbatim, translated or not (ADR-0007).
    assertThat(finding.messageEn()).isEqualTo("Seller electronic address MUST be provided");
  }

  /**
   * M5: a catalogued rule gets a real German message instead of M4's German frame around English.
   * This is the milestone closing the gap M4 recorded honestly rather than papering over.
   */
  @Test
  void usesTheGermanTranslationWhenTheRuleIsCatalogued() {
    Finding finding =
        PeppolValidationStage.toFinding(
            error(
                EErrorLevel.ERROR,
                "PEPPOL-EN16931-R020",
                "Seller electronic address MUST be provided"));

    assertThat(finding.messageDe())
        .isEqualTo(
            "Peppol BIS Billing 3.0: Die elektronische Adresse des Verkäufers (BT-34) muss"
                + " angegeben werden.")
        // A German message that still quotes the English rule text would mean the lookup silently
        // missed.
        .doesNotContain("MUST be provided");
  }

  @Test
  void keepsTheGermanFrameAroundEnglishForAnUncataloguedRule() {
    // The fallback is deliberately unchanged from M4: worse than a translation, never wrong. Uses a
    // rule id the catalog does not cover, so this test keeps testing the fallback even as the
    // catalog
    // grows.
    Finding finding =
        PeppolValidationStage.toFinding(
            error(
                EErrorLevel.WARN, "UBL-CR-412", "cbc:CompanyLegalFormCode should not be present"));

    assertThat(finding.messageDe())
        .isEqualTo("Peppol BIS Billing 3.0: cbc:CompanyLegalFormCode should not be present");
  }

  @Test
  void everyCataloguedGermanMessageIsUsableAsAFinding() {
    // Guards the whole catalog at once against the two ways an entry can be wrong in a way no
    // individual test would notice: a blank value, or one long enough that Finding's own cap would
    // throw out of validate() and break the never-throws contract.
    assertThat(PeppolMessagesDe.all()).isNotEmpty();
    assertThat(PeppolMessagesDe.all())
        .allSatisfy(
            (ruleId, german) -> {
              assertThat(german).as("German message for %s", ruleId).isNotBlank();
              assertThat(german)
                  .as("German message for %s must fit Finding's cap", ruleId)
                  .hasSizeLessThanOrEqualTo(BoundedText.MAX_MESSAGE_DETAIL);
              // Constructing the finding is the real assertion: it runs Finding's own invariants.
              assertThat(Finding.of(Severity.ERROR, ruleId, null, german, "en")).isNotNull();
            });
  }

  @Test
  void theCatalogCoversEveryPeppolSpecificRuleOfThePinnedRuleSet() {
    // The PEPPOL-EN16931-R* layer is small and entirely relevant to an Austrian filer, so partial
    // coverage of it would be an oversight rather than a scoping decision. The EN 16931 layer is
    // deliberately partial — see PeppolMessagesDe's Javadoc.
    assertThat(PeppolMessagesDe.all().keySet())
        .contains(
            "PEPPOL-EN16931-R001",
            "PEPPOL-EN16931-R002",
            "PEPPOL-EN16931-R003",
            "PEPPOL-EN16931-R004",
            "PEPPOL-EN16931-R005",
            "PEPPOL-EN16931-R007",
            "PEPPOL-EN16931-R008",
            "PEPPOL-EN16931-R010",
            "PEPPOL-EN16931-R020",
            "PEPPOL-EN16931-R040",
            "PEPPOL-EN16931-R041",
            "PEPPOL-EN16931-R042",
            "PEPPOL-EN16931-R043",
            "PEPPOL-EN16931-R044",
            "PEPPOL-EN16931-R046",
            "PEPPOL-EN16931-R051",
            "PEPPOL-EN16931-R053",
            "PEPPOL-EN16931-R054",
            "PEPPOL-EN16931-R055",
            "PEPPOL-EN16931-R061",
            "PEPPOL-EN16931-R080",
            "PEPPOL-EN16931-R100",
            "PEPPOL-EN16931-R101",
            "PEPPOL-EN16931-R110",
            "PEPPOL-EN16931-R111",
            "PEPPOL-EN16931-R120",
            "PEPPOL-EN16931-R121",
            "PEPPOL-EN16931-R130");
  }

  /**
   * M5 hostile review, F9. A test with this name existed and asserted that {@code forRule("BR-02")}
   * was present and {@code forRule("does-not-exist")} was empty — a lookup test wearing a bounding
   * test's name, which is worse than no test, because the test list read as though the case were
   * covered. This one constructs a translation no real entry has and asserts the truncation.
   */
  @Test
  void boundsAnOverlongTranslationTheSameWayAsForeignText() {
    String overlong = "Ü".repeat(BoundedText.MAX_MESSAGE_DETAIL + 500);

    String bounded = PeppolValidationStage.boundedGerman(overlong);

    assertThat(bounded).hasSize(BoundedText.MAX_MESSAGE_DETAIL).endsWith("…");
    // The reason the cap exists at all: Finding throws above its own limit, and toFinding runs
    // inside validate(), whose contract is that it never throws. The bounded value must be usable.
    assertThat(
            Finding.of(Severity.ERROR, "BR-02", null, "Peppol BIS Billing 3.0: " + bounded, "en"))
        .isNotNull();
  }

  @Test
  void leavesATranslationThatFitsExactlyAsItIs() {
    // The other side of the boundary, so the cap cannot silently start truncating real entries.
    String exact = "Ü".repeat(BoundedText.MAX_MESSAGE_DETAIL);

    assertThat(PeppolValidationStage.boundedGerman(exact)).isEqualTo(exact);
  }

  @Test
  void looksTheTranslationUpByTheRuleSetsOwnAssertionId() {
    // What the old boundsAnOverlongTranslation… test actually asserted, under its real name.
    assertThat(PeppolMessagesDe.forRule("BR-02")).isPresent();
    assertThat(PeppolMessagesDe.forRule("does-not-exist")).isEmpty();
  }

  /**
   * M5 hostile review, F10. {@code PeppolMessagesDe.SIZE_NOTE} is rendered into that class's
   * Javadoc through {@code {@value}} and described there as "asserted by the stage test". It was
   * asserted by nothing — the constant appeared nowhere outside its own file — and it was wrong: it
   * said 78, the catalogue held 80. The number is also repeated in {@code docs/worklog.md},
   * including in the checklist for the mandatory 2026-08-17 Peppol 2026.5 upgrade, so the count a
   * future maintainer re-verifies against the new assertion texts was the wrong count.
   *
   * <p>This is what makes the sentence true. The assertion is deliberately on the exact number
   * rather than a range: a documentation constant that drifts silently is the thing being fixed.
   */
  @Test
  void theDocumentedCatalogueSizeIsTheActualCatalogueSize() {
    assertThat(PeppolMessagesDe.SIZE_NOTE)
        .withFailMessage(
            "PeppolMessagesDe.SIZE_NOTE says \"%s\" but the catalogue holds %d entries. Update the"
                + " constant — it is rendered into the class Javadoc and quoted in docs/worklog.md.",
            PeppolMessagesDe.SIZE_NOTE, PeppolMessagesDe.all().size())
        .isEqualTo(PeppolMessagesDe.all().size() + " of them");
  }

  @Test
  void fallsBackToPeppol01WhenTheDiagnosticCarriesNoAssertionId() {
    assertThat(PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, null, "boom")).ruleId())
        .isEqualTo(RuleIds.PEPPOL_01);
    assertThat(PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, "  ", "boom")).ruleId())
        .isEqualTo(RuleIds.PEPPOL_01);
  }

  @Test
  void mapsBelowErrorLevelsToAWarning() {
    assertThat(
            PeppolValidationStage.toFinding(error(EErrorLevel.WARN, "UBL-CR-412", "should not"))
                .severity())
        .isEqualTo(Severity.WARN);
    assertThat(
            PeppolValidationStage.toFinding(error(EErrorLevel.FATAL_ERROR, "BR-01", "must"))
                .severity())
        .isEqualTo(Severity.ERROR);
  }

  /**
   * A Schematron message can echo document content verbatim, so it is bounded before it reaches
   * {@code Finding} — whose own caps throw when exceeded. Same reachable-crash class the M2 hostile
   * review closed for the XSD stage; closed here by construction rather than rediscovered.
   */
  @Test
  void boundsForeignTextBeforeItReachesTheFinding() {
    String hostile = "x".repeat(BoundedText.MAX_MESSAGE_DETAIL * 3);

    Finding finding = PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, "BR-01", hostile));

    assertThat(finding.messageEn()).hasSize(BoundedText.MAX_MESSAGE_DETAIL);
  }

  @Test
  void boundsAnOverlongRuleIdRatherThanLettingFindingReject() {
    String hostileId = "R".repeat(BoundedText.MAX_RULE_ID * 3);

    Finding finding = PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, hostileId, "boom"));

    assertThat(finding.ruleId()).hasSize(BoundedText.MAX_RULE_ID);
  }

  @Test
  void fallsBackToAFixedDetailWhenTheRuleSetSuppliesNoText() {
    Finding finding = PeppolValidationStage.toFinding(error(EErrorLevel.ERROR, "BR-01", null));

    assertThat(finding.messageEn()).contains("Unbekannter Peppol-Prüffehler");
  }

  /**
   * 2026.5 replaced R007's profile check. Until then {@code $profile} was derived by matching
   * {@code urn:fdc:peppol.eu:2017:poacc:billing:NN:1.0} and pulling out {@code NN}; it is now a
   * closed allowlist of four identifiers, two of which ({@code urn:peppol:france:billing:regulated}
   * and {@code …:non-regulated}) do not have that shape at all.
   *
   * <p>So the German message describing a required format is no longer merely imprecise, it is
   * wrong: it would tell an Austrian filer whose document was rejected to go and construct an
   * identifier that the rule set does not accept. A translation that misdirects is worse than the
   * untranslated English fallback, which is the whole premise of this catalogue.
   */
  @Test
  void theGermanMessageForR007DoesNotPromiseTheRemovedNnFormat() {
    String german = PeppolMessagesDe.all().get("PEPPOL-EN16931-R007");

    assertThat(german)
        .as("R007 no longer matches a NN-numbered pattern; it checks a fixed allowlist")
        .doesNotContain("NN")
        .doesNotContain("urn:fdc:peppol.eu:2017:poacc:billing:");
  }

  /**
   * R004 was always a {@code starts-with} check, and 2026.5 additionally forbids {@code ::} inside
   * the identifier. The German text said the value "muss den Wert … haben" — an equality claim that
   * was already imprecise under 2025.11 and now also omits a constraint a document can fail on.
   */
  @Test
  void theGermanMessageForR004DescribesAPrefixAndTheNewSeparatorRule() {
    String german = PeppolMessagesDe.all().get("PEPPOL-EN16931-R004");

    assertThat(german).as("the rule is starts-with, not equality").contains("beginnen");
    assertThat(german).as("2026.5 additionally rejects '::'").contains("::");
  }

  /**
   * The pin is a deliberate choice, so nothing about it expires on its own — which is exactly how a
   * pinned rule set goes stale without anyone noticing. OpenPeppol publishes each release with the
   * date it becomes mandatory ({@code VALID_PER}), and running a superseded rule set after that
   * date means this validator would accept documents the receiving network rejects, and reject
   * documents it accepts.
   *
   * <p>Before this test the deadline lived in {@code docs/owner-checklist.md} and in the owner's
   * memory. Now the build fails on the day a newer rule set becomes mandatory, and names it. This
   * is the one tripwire here that fires without anybody editing the repository first.
   *
   * <p>The candidate set is read out of the phive-rules artefact rather than listed here, so a
   * dependency bump that publishes a new rule set is picked up with no edit to this file.
   */
  @Test
  void noNewerRuleSetIsAlreadyMandatory() {
    int[] pinned = versionOf(PeppolValidationStage.RULE_SET_VERSION);
    LocalDate today = LocalDate.now();

    Map<String, LocalDate> published = publishedRuleSets();
    assertThat(published)
        .as(
            "phive-rules ships several dated Peppol rule sets; none were found, so this guard would"
                + " never fire and the pin would be unprotected")
        .hasSizeGreaterThan(1);

    assertThat(published)
        .allSatisfy(
            (version, mandatoryFrom) -> {
              int[] candidate = versionOf(version);
              boolean isNewer =
                  candidate[0] > pinned[0]
                      || (candidate[0] == pinned[0] && candidate[1] > pinned[1]);
              if (isNewer) {
                assertThat(mandatoryFrom)
                    .as(
                        "OpenPeppol rule set %s became mandatory on %s and this validator is still"
                            + " pinned to %s. Upgrade PeppolValidationStage to"
                            + " PeppolValidation%s_%02d and re-run the corpus (ADR-0007).",
                        version,
                        mandatoryFrom,
                        PeppolValidationStage.RULE_SET_VERSION,
                        candidate[0],
                        candidate[1])
                    .isAfter(today);
              }
            });
  }

  private static boolean hasField(Class<?> c, String name) {
    try {
      c.getField(name);
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  /** {@code "2026.5"} to {@code [2026, 5]}, so releases order numerically and not as text. */
  private static int[] versionOf(String versionStr) {
    String[] parts = versionStr.split("\\.");
    return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
  }

  /**
   * Every dated Peppol rule set the pinned phive-rules artefact publishes, mapped to the date it
   * becomes mandatory. Found by listing the jar that {@code PeppolValidation} itself was loaded
   * from, so it needs no hard-coded list and no hard-coded path.
   */
  private static Map<String, LocalDate> publishedRuleSets() {
    Pattern dated =
        Pattern.compile("com/helger/phive/peppol/(PeppolValidation(\\d{4})_(\\d{2}))\\.class");
    Map<String, LocalDate> found = new HashMap<>();
    try {
      URL location =
          Class.forName("com.helger.phive.peppol.PeppolValidation")
              .getProtectionDomain()
              .getCodeSource()
              .getLocation();
      Path jar = Path.of(location.toURI());
      if (!Files.isRegularFile(jar)) {
        return found; // exploded classes: the size guard above turns this into a visible failure
      }
      try (JarFile jarFile = new JarFile(jar.toFile())) {
        for (JarEntry entry : java.util.Collections.list(jarFile.entries())) {
          Matcher m = dated.matcher(entry.getName());
          if (!m.matches()) {
            continue;
          }
          Class<?> c = Class.forName("com.helger.phive.peppol." + m.group(1));
          // Not every release carries a mandatory-from date: at phive-rules 4.4.1, 2026.3 declares
          // VERSION_STR and no VALID_PER. An undated release cannot be shown to be in force, so it
          // is skipped rather than guessed at — this guard answers "has a dated successor already
          // taken effect", and stays silent where the artefact itself is silent.
          if (!hasField(c, "VALID_PER")) {
            continue;
          }
          found.put(
              (String) c.getField("VERSION_STR").get(null),
              (LocalDate) c.getField("VALID_PER").get(null));
        }
      }
    } catch (ReflectiveOperationException | IOException | URISyntaxException e) {
      throw new AssertionError("could not enumerate the published Peppol rule sets", e);
    }
    return found;
  }

  /**
   * A German translation for a rule the pinned rule set no longer contains is dead text: it can
   * never be rendered, and it quietly inflates {@link PeppolMessagesDe#SIZE_NOTE} into a claim
   * about coverage that is no longer true.
   *
   * <p>This is the test the 2026.5 upgrade needed and did not have. Its sibling above reads as
   * though it checks the catalogue against the pinned rule set, but it compares against a list
   * typed into this file, so it is blind in exactly this direction; and the size test only compares
   * the catalogue with itself. Moving from 2025.11 to 2026.5 deleted {@code BR-CO-25} and every
   * test in the module stayed green.
   *
   * <p>The expected set is derived from the artefacts phive-rules actually ships, located through
   * {@link PeppolValidationStage#RULE_SET_VERSION}, so this follows the pin automatically instead
   * of becoming another hand-maintained list that has to be remembered.
   */
  @Test
  void everyCatalogueEntryNamesARuleThePinnedRuleSetStillDeclares() {
    Set<String> declared = ruleIdsDeclaredByThePinnedRuleSet();

    // Guard against a vacuous pass: if the resource path ever stops resolving, `declared` would be
    // empty and this test would still be meaningful only by accident. The real rule set declares
    // roughly a thousand assertions across the two layers.
    assertThat(declared)
        .as(
            "rule-set artefacts for %s were located and parsed",
            PeppolValidationStage.RULE_SET_VERSION)
        .hasSizeGreaterThan(500);

    assertThat(PeppolMessagesDe.all().keySet())
        .allSatisfy(
            id ->
                assertThat(declared)
                    .as(
                        "PeppolMessagesDe carries a German message for %s, which OpenPeppol rule set"
                            + " %s does not declare. Remove the entry (and fix SIZE_NOTE) rather"
                            + " than shipping a translation that can never be rendered.",
                        id, PeppolValidationStage.RULE_SET_VERSION)
                    .contains(id));
  }

  /**
   * Every assertion identifier the pinned rule set declares, read out of the two XSLT layers this
   * stage actually executes. OpenPeppol renders each identifier into its own message text as {@code
   * [BR-CO-25]-…}, which is what makes them recoverable without an XSLT engine. Character classes
   * such as {@code [A-Z]} match the same shape and are harmless: they are extra members of a set
   * used only for containment checks, so they can hide no real absence.
   */
  private static Set<String> ruleIdsDeclaredByThePinnedRuleSet() {
    Pattern bracketed = Pattern.compile("\\[([A-Z][A-Z0-9-]+)\\]");
    Set<String> ids = new HashSet<>();
    for (String layer : List.of("CEN-EN16931-UBL", "PEPPOL-EN16931-UBL")) {
      String resource =
          "external/schematron/openpeppol/"
              + PeppolValidationStage.RULE_SET_VERSION
              + "/xslt/"
              + layer
              + ".xslt";
      try (InputStream in =
          PeppolValidationStageTest.class.getClassLoader().getResourceAsStream(resource)) {
        assertThat(in).as("rule-set artefact %s is on the test classpath", resource).isNotNull();
        Matcher m = bracketed.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        while (m.find()) {
          ids.add(m.group(1));
        }
      } catch (IOException e) {
        throw new AssertionError("could not read " + resource, e);
      }
    }
    return ids;
  }

  private static com.helger.diagnostics.error.IError error(
      EErrorLevel level, String id, String text) {
    return SingleError.builder().errorLevel(level).errorID(id).errorText(text).build();
  }
}
