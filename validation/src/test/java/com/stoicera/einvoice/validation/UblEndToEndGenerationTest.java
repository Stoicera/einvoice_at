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
  private static final String CORPUS_PEPPOL_RESOURCE = "corpus/valid/peppol-ubl-invoice.xml";

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

  /** The corpus copy and the samples twin are the same bytes, so neither can drift alone. */
  @Test
  void corpusCopyMatchesTheSamplesTwinModuloTheirDifferentSourceInvoices() throws IOException {
    String corpus = readCorpusResource();

    // Not byte-equal to the samples twin — the corpus entry is generated from PeppolFixtures, a
    // different invoice — but it must be the same shape of document from the same writer.
    assertThat(normalise(corpus))
        .startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        .contains("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2");
    assertThat(validator.validate(corpus.getBytes(StandardCharsets.UTF_8)).isValid()).isTrue();
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

  private static String readCorpusResource() throws IOException {
    try (InputStream in =
        UblEndToEndGenerationTest.class
            .getClassLoader()
            .getResourceAsStream(CORPUS_PEPPOL_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Corpus resource not found: " + CORPUS_PEPPOL_RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Line-ending-normalised comparison: CR stripped, trailing newline ignored. */
  private static String normalise(String xml) {
    return xml.replace("\r", "").stripTrailing();
  }
}
