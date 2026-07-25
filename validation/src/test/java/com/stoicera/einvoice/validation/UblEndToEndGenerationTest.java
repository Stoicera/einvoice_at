package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.formats.ubl.Ubl21CreditNoteStrategy;
import com.stoicera.einvoice.formats.ubl.Ubl21InvoiceStrategy;
import com.stoicera.einvoice.mapping.json.InvoiceJsonReader;
import com.stoicera.einvoice.mapping.ubl.InvoiceToUblMapper;
import com.stoicera.einvoice.mapping.ubl.UblDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;
import org.junit.jupiter.api.Test;

/**
 * The M4 acceptance test in code, and the UBL counterpart of {@link EndToEndGenerationTest}: the
 * same canonical sample, generated as Peppol BIS Billing 3.0 UBL and judged by the <strong>official
 * OpenPeppol rule set</strong>.
 *
 * <p>{@code samples/invoice-b2g-sample.json} → {@link InvoiceJsonReader} → {@link
 * InvoiceToUblMapper} → {@link Ubl21InvoiceStrategy#write} → {@link InvoiceValidator#validate}. A
 * clean report means one specific thing here that it does not mean for ebInterface: for ebInterface
 * the validator applies rules this project wrote (AUSTRIAPRO publishes none), whereas for UBL it
 * applies rules OpenPeppol publishes and this project only executes. Zero findings is therefore an
 * external verdict, not a self-assessment — the closest thing to the ebInterface portal Abnahme
 * that can run in CI.
 *
 * <p>The committed twin {@code samples/invoice-b2g-sample.ubl.xml} is asserted byte-for-byte
 * (line-ending-normalised) equal to what the pipeline just wrote, exactly as its ebInterface
 * sibling is, so the committed artifact provably is the pipeline's own output rather than something
 * hand-edited into agreement.
 *
 * <p>Paths resolve against the module directory: Surefire runs with the working directory set to
 * {@code validation/}, so {@code Path.of("..", "samples", …)} climbs to the repository root.
 */
class UblEndToEndGenerationTest {

  private static final Path SAMPLES = Path.of("..", "samples");
  private static final Path SAMPLE_JSON = SAMPLES.resolve("invoice-b2g-sample.json");
  private static final Path SAMPLE_UBL_TWIN = SAMPLES.resolve("invoice-b2g-sample.ubl.xml");
  private static final String CORPUS_PEPPOL_INVOICE = "corpus/valid/peppol-ubl-invoice.xml";
  private static final String CORPUS_PEPPOL_CREDIT_NOTE = "corpus/valid/peppol-ubl-creditnote.xml";

  private static final InvoiceToUblMapper MAPPER = new InvoiceToUblMapper();
  private static final Ubl21InvoiceStrategy INVOICES = new Ubl21InvoiceStrategy();
  private static final Ubl21CreditNoteStrategy CREDIT_NOTES = new Ubl21CreditNoteStrategy();

  private final InvoiceValidator validator = new InvoiceValidator();

  @Test
  void b2gSampleGeneratesAPeppolValidUblInvoice() throws IOException {
    String writtenXml = generateFromSample();

    ValidationReport report = validator.validate(writtenXml.getBytes(StandardCharsets.UTF_8));

    assertThat(report.sourceFormat()).isEqualTo(DocumentFormat.UBL_INVOICE.sourceFormat());
    assertThat(report.profile()).isEqualTo(InvoiceValidator.PROFILE_PEPPOL_BIS_3);
    assertThat(report.findings())
        .withFailMessage(
            "the sample must be Peppol-clean; the official rule set reported: %s",
            report.findings())
        .isEmpty();
    assertThat(report.isValid()).isTrue();
  }

  /**
   * The committed twin is the pipeline's own output.
   *
   * <p>On an intentional mapper or writer change this fails and reports the fresh output as its
   * "actual" value; copy that verbatim over {@code samples/invoice-b2g-sample.ubl.xml}. There is no
   * {@code --generate} mode: this test is the single source of the expected bytes, exactly as the
   * ebInterface twin's is.
   */
  @Test
  void committedUblTwinMatchesTheFreshlyGeneratedXml() throws IOException {
    String generated = generateFromSample();
    String committed = Files.readString(SAMPLE_UBL_TWIN, StandardCharsets.UTF_8);

    assertThat(normalise(generated)).isEqualTo(normalise(committed));
  }

  /**
   * The committed Peppol corpus entries are their generator's own output, byte for byte.
   *
   * <p>M4 hostile review, finding F5. This test used to be named for a comparison it did not make:
   * it was called {@code corpusCopyMatchesTheSamplesTwin…} and carried a comment claiming "the
   * corpus copy and the samples twin are the same bytes, so neither can drift alone", while the
   * body compared the corpus file to <em>nothing</em> — it asserted only that the file began with
   * an XML declaration, mentioned the UBL namespace, and was valid. Three assertions that hold for
   * any Peppol-valid invoice in existence. A reader scanning the test list saw a drift guard where
   * none existed, which is worse than an obviously missing test.
   *
   * <p>The corpus entries are not copies of the samples twin — {@code samples/README.md} never
   * claimed they were, and they are generated from {@link PeppolFixtures}, a different invoice. So
   * the honest guard is the one their own README promises: regenerate from the documented chain and
   * demand the committed bytes match, exactly as {@link
   * #committedUblTwinMatchesTheFreshlyGeneratedXml()} does for the samples twin.
   *
   * <p>On an intentional mapper or writer change this fails and reports the fresh output as its
   * "actual" value; copy that verbatim over the corpus file.
   */
  @Test
  void committedPeppolCorpusInvoiceIsItsGeneratorsOwnOutput() throws IOException {
    String generated = INVOICES.write(ublInvoiceOf(PeppolFixtures.peppolReadyInvoice()));

    assertThat(normalise(readCorpusResource(CORPUS_PEPPOL_INVOICE)))
        .isEqualTo(normalise(generated));
  }

  /** The credit note is a separate document type with a separate rule set, so it is pinned too. */
  @Test
  void committedPeppolCorpusCreditNoteIsItsGeneratorsOwnOutput() throws IOException {
    String generated =
        CREDIT_NOTES.write(
            ((UblDocument.CreditNote) MAPPER.map(PeppolFixtures.peppolReadyCreditNote()))
                .document());

    assertThat(normalise(readCorpusResource(CORPUS_PEPPOL_CREDIT_NOTE)))
        .isEqualTo(normalise(generated));
  }

  /**
   * And the committed bytes are Peppol-clean — the claim {@code corpus/README.md} makes for them,
   * asserted rather than assumed. Kept separate from the drift guards above so a failure says which
   * of the two things broke: the file drifted, or the rule set stopped accepting it.
   */
  @Test
  void committedPeppolCorpusEntriesAreAcceptedByTheOfficialRuleSet() throws IOException {
    for (String resource : new String[] {CORPUS_PEPPOL_INVOICE, CORPUS_PEPPOL_CREDIT_NOTE}) {
      ValidationReport report =
          validator.validate(readCorpusResource(resource).getBytes(StandardCharsets.UTF_8));

      assertThat(report.findings())
          .withFailMessage("%s must be Peppol-clean; the rule set reported: %s", resource, report)
          .isEmpty();
    }
  }

  private static String generateFromSample() throws IOException {
    try (InputStream json = Files.newInputStream(SAMPLE_JSON)) {
      Invoice invoice = new InvoiceJsonReader().read(json);
      return switch (MAPPER.map(invoice)) {
        case UblDocument.CommercialInvoice(var document) -> INVOICES.write(document);
        case UblDocument.CreditNote(var document) -> CREDIT_NOTES.write(document);
      };
    }
  }

  private static InvoiceType ublInvoiceOf(Invoice invoice) {
    return ((UblDocument.CommercialInvoice) MAPPER.map(invoice)).document();
  }

  private static String readCorpusResource(String resource) throws IOException {
    try (InputStream in =
        UblEndToEndGenerationTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Corpus resource not found: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Line-ending-normalised comparison: CR stripped, trailing newline ignored. */
  private static String normalise(String xml) {
    return xml.replace("\r", "").stripTrailing();
  }
}
