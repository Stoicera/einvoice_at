package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.mapping.ebinterface.InvoiceToEbInterface61Mapper;
import com.stoicera.einvoice.mapping.json.InvoiceJsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The M2 acceptance test in code: the full generation chain for the canonical B2G sample, end to
 * end, asserted valid against the Austrian B2G profile.
 *
 * <p>{@code samples/invoice-b2g-sample.json} → {@link InvoiceJsonReader} → {@link
 * InvoiceToEbInterface61Mapper} → {@link EbInterface61Strategy#write} → {@link
 * EbInterface61Validator#validate}. A clean report (zero findings) proves the platform generates a
 * document that passes its own validator; the committed twin {@code
 * samples/invoice-b2g-sample.ebinterface.xml} is asserted byte-for-byte (line-ending-normalized)
 * equal to what the pipeline just wrote, so the committed artifact provably is the pipeline's own
 * output — it is the file uploaded once to the official ebInterface portal check (owner Abnahme).
 * The golden-file corpus copy {@code corpus/valid/b2g-full.xml} is pinned the same way, against the
 * samples twin, so all three (pipeline output, samples twin, corpus copy) are provably identical.
 *
 * <p>Paths resolve against the module directory: Surefire runs with the working directory set to
 * {@code validation/}, so {@code Path.of("..", "samples", …)} climbs one level to the repository
 * root where {@code samples/} lives (it sits outside every Maven module on purpose).
 */
class EndToEndGenerationTest {

  private static final Path SAMPLES = Path.of("..", "samples");
  private static final Path SAMPLE_JSON = SAMPLES.resolve("invoice-b2g-sample.json");
  private static final Path SAMPLE_XML_TWIN = SAMPLES.resolve("invoice-b2g-sample.ebinterface.xml");
  private static final String CORPUS_B2G_FULL_RESOURCE = "corpus/valid/b2g-full.xml";

  private final EbInterface61Validator validator = new EbInterface61Validator();

  @Test
  void b2gSampleGeneratesAValidEbInterface61Document() throws IOException {
    String writtenXml = generateFromSample();

    ValidationReport report = validator.validate(writtenXml.getBytes(StandardCharsets.UTF_8));

    assertThat(report.sourceFormat()).isEqualTo(EbInterface61Validator.SOURCE_EBINTERFACE_61);
    assertThat(report.profile()).isEqualTo(EbInterface61Validator.PROFILE_AT_B2G);
    assertThat(report.findingsOf(Severity.ERROR)).isEmpty();
    assertThat(report.findingsOf(Severity.WARN)).isEmpty();
    assertThat(report.isValid()).isTrue();
    // The B2G sample is the fully-populated happy path: it must be spotless, not merely error-free.
    assertThat(report.findings()).isEmpty();
  }

  @Test
  void committedTwinMatchesTheFreshlyGeneratedXml() throws IOException {
    String writtenXml = generateFromSample();
    String committedTwin = Files.readString(SAMPLE_XML_TWIN, StandardCharsets.UTF_8);

    // Normalize line endings only: strip CR (Windows checkouts) and any trailing newline, so the
    // assertion is about XML content, not the platform that wrote the working copy. Regenerate the
    // committed twin by copying this "actual" whenever an intentional mapper change moves it.
    assertThat(normalize(writtenXml))
        .as("committed samples/invoice-b2g-sample.ebinterface.xml must equal the pipeline output")
        .isEqualTo(normalize(committedTwin));
  }

  @Test
  void corpusCopyMatchesTheSamplesTwin() throws IOException {
    String committedTwin = Files.readString(SAMPLE_XML_TWIN, StandardCharsets.UTF_8);
    String corpusCopy = readClasspathResource(CORPUS_B2G_FULL_RESOURCE);

    // corpus/README.md claims corpus/valid/b2g-full.xml and the samples twin are the same pipeline
    // output pinned twice; this assertion is what makes that claim true rather than aspirational.
    assertThat(normalize(corpusCopy))
        .as(
            "corpus/valid/b2g-full.xml must equal %s (both pin the same pipeline output)",
            SAMPLE_XML_TWIN)
        .isEqualTo(normalize(committedTwin));
  }

  /** Runs the JSON → canonical → ebInterface 6.1 chain for the committed B2G sample. */
  private static String generateFromSample() throws IOException {
    try (InputStream json = Files.newInputStream(SAMPLE_JSON)) {
      Invoice invoice = new InvoiceJsonReader().read(json);
      Ebi61InvoiceType ebi = new InvoiceToEbInterface61Mapper().map(invoice);
      return new EbInterface61Strategy().write(ebi);
    }
  }

  private static String readClasspathResource(String resource) throws IOException {
    try (InputStream in =
        EndToEndGenerationTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Missing classpath resource: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String normalize(String xml) {
    return xml.replace("\r", "").stripTrailing();
  }
}
