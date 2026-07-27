package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.validation.ValidationReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pipeline is observable stage by stage, which is what M6's "OTel traces across the pipeline
 * stages" needs from this module (ADR-0012).
 *
 * <p>These tests are about the <em>sequence</em> of observed stages, not about timing: the sequence
 * is the part a tracer turns into a trace, and it is the part that silently rots when a stage is
 * added to {@link InvoiceValidator} and nobody wires it through the observer. Asserting the exact
 * list — rather than "contains xsd" — is what makes a forgotten stage a test failure.
 */
class ValidationObserverTest {

  /**
   * Records the stage names in the order they were entered, and passes the work straight through.
   */
  private static final class RecordingObserver implements ValidationObserver {
    private final List<String> stages = new ArrayList<>();

    @Override
    public <T> T observe(String stageName, Supplier<T> stage) {
      stages.add(stageName);
      return stage.get();
    }
  }

  @Test
  @DisplayName(
      "the ebInterface pipeline observes parse, detection, XSD, Schematron, business rules")
  void observesEveryEbInterfaceStage() {
    RecordingObserver observer = new RecordingObserver();

    ValidationReport report =
        new InvoiceValidator(observer)
            .validate(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(report.isValid()).isTrue();
    assertThat(observer.stages)
        .containsExactly(
            ValidationObserver.STAGE_PARSE,
            ValidationObserver.STAGE_FORMAT_DETECTION,
            ValidationObserver.STAGE_XSD,
            ValidationObserver.STAGE_SCHEMATRON,
            ValidationObserver.STAGE_BUSINESS_RULES);
  }

  @Test
  @DisplayName("the UBL pipeline observes parse, detection and the single Peppol stage")
  void observesThePeppolStage() {
    RecordingObserver observer = new RecordingObserver();

    new InvoiceValidator(observer).validate(corpus("valid/peppol-ubl-invoice.xml"));

    assertThat(observer.stages)
        .containsExactly(
            ValidationObserver.STAGE_PARSE,
            ValidationObserver.STAGE_FORMAT_DETECTION,
            ValidationObserver.STAGE_PEPPOL);
  }

  @Test
  @DisplayName("a terminal stop is visible as an absence: malformed input observes only the parse")
  void stopsObservingWhereThePipelineStops() {
    RecordingObserver observer = new RecordingObserver();

    new InvoiceValidator(observer).validate(TestDocuments.bytes(TestDocuments.malformed()));

    assertThat(observer.stages).containsExactly(ValidationObserver.STAGE_PARSE);
  }

  @Test
  @DisplayName(
      "an XSD failure stops the pipeline, so Schematron and business rules are not observed")
  void doesNotObserveStagesTheXsdGateSkips() {
    RecordingObserver observer = new RecordingObserver();

    new InvoiceValidator(observer)
        .validate(TestDocuments.bytes(TestDocuments.brokenEbInterface61()));

    assertThat(observer.stages)
        .containsExactly(
            ValidationObserver.STAGE_PARSE,
            ValidationObserver.STAGE_FORMAT_DETECTION,
            ValidationObserver.STAGE_XSD);
  }

  @Test
  @DisplayName("an oversized upload is refused before anything is observed at all")
  void observesNothingForAnOversizedUpload() {
    RecordingObserver observer = new RecordingObserver();

    ValidationReport report =
        new InvoiceValidator(observer).validate(new byte[InvoiceValidator.MAX_INPUT_BYTES + 1]);

    assertThat(report.findings()).singleElement().extracting("ruleId").isEqualTo(RuleIds.XML_02);
    assertThat(observer.stages).isEmpty();
  }

  @Test
  @DisplayName("detectFormat observes the two stages it actually runs, and no more")
  void observesTheDetectionEntryPoint() {
    RecordingObserver observer = new RecordingObserver();

    DocumentFormat format =
        new InvoiceValidator(observer)
            .detectFormat(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(format).isEqualTo(DocumentFormat.EBINTERFACE_61);
    assertThat(observer.stages)
        .containsExactly(ValidationObserver.STAGE_PARSE, ValidationObserver.STAGE_FORMAT_DETECTION);
  }

  @Test
  @DisplayName("the no-argument constructor and NONE produce the same report")
  void theDefaultObserverChangesNothing() {
    byte[] document = TestDocuments.bytes(TestDocuments.ebInterface61WithBrokenIban());

    ValidationReport unobserved = new InvoiceValidator().validate(document);
    ValidationReport observed = new InvoiceValidator(ValidationObserver.NONE).validate(document);

    assertThat(observed.findings()).isEqualTo(unobserved.findings());
    assertThat(observed.isValid()).isEqualTo(unobserved.isValid());
    assertThat(observed.profile()).isEqualTo(unobserved.profile());
  }

  @Test
  @DisplayName("NONE returns the stage's own value rather than a substitute")
  void noneIsTransparent() {
    Object sentinel = new Object();

    assertThat(ValidationObserver.NONE.observe("anything", () -> sentinel)).isSameAs(sentinel);
  }

  @Test
  @DisplayName("a null observer is refused at construction, not at the first document")
  void refusesANullObserver() {
    assertThatThrownBy(() -> new InvoiceValidator(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("observer");
  }

  private static byte[] corpus(String name) {
    try (var in = ValidationObserverTest.class.getResourceAsStream("/corpus/" + name)) {
      if (in == null) {
        throw new IllegalStateException("corpus file missing: " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
