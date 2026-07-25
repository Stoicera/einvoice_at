package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.formats.ubl.Ubl21CreditNoteStrategy;
import com.stoicera.einvoice.formats.ubl.Ubl21InvoiceStrategy;
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import com.stoicera.einvoice.mapping.conversion.ConversionLosses;
import com.stoicera.einvoice.mapping.conversion.ConversionNotes;
import com.stoicera.einvoice.mapping.conversion.TargetFormat;
import com.stoicera.einvoice.mapping.ebinterface.EbInterface61ToInvoiceMapper;
import com.stoicera.einvoice.mapping.ebinterface.InvoiceToEbInterface61Mapper;
import com.stoicera.einvoice.mapping.ubl.InvoiceToUblMapper;
import com.stoicera.einvoice.mapping.ubl.UblDocument;
import com.stoicera.einvoice.mapping.ubl.UblToInvoiceMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * <strong>The M4 acceptance test MILESTONES asks for by name:</strong> "Golden-Files für Roundtrips
 * (ebInterface→UBL→ebInterface, dokumentierte Abweichungen)."
 *
 * <p>M4 shipped without it (M4 hostile review, finding F3). What existed were two
 * <em>same-format</em> jqwik round trips and one integration test that converted out and back and
 * then asserted the result merely contained a namespace string. Neither can see a cross-format
 * defect, and the gap was not academic: writing this class immediately found one (F3a — the
 * exemption comment grew by one category code on every trip, unboundedly, in a persisted field).
 *
 * <h2>What a cross-format round trip proves that a same-format one cannot</h2>
 *
 * <p>A same-format property answers "do these two mappers agree with each other". This answers "do
 * the two <em>pairs</em> agree about the same invoice" — it drives the real chain, ebInterface
 * bytes → canonical → UBL bytes → canonical → ebInterface bytes, and compares documents rather than
 * canonical models. That distinction is what caught F3a: the corruption lived in emitted XML and
 * was invisible to every assertion over an {@code Invoice}.
 *
 * <h2>The golden files</h2>
 *
 * <p>Deliberately not new fixtures. The golden file for each case is the <em>input document
 * itself</em> — the corpus entries and the committed samples twin, all already pinned byte-for-byte
 * against the live pipeline elsewhere. A round trip that returns its own input is the strongest
 * golden-file assertion available and cannot drift from the corpus, because it is the corpus.
 *
 * <h2>Dokumentierte Abweichungen</h2>
 *
 * <p>{@link #ublToEbInterfaceAndBackLosesExactlyTheDocumentedFields()} is the other half of the
 * milestone's wording. The two directions are not symmetric, and the asymmetry is the interesting
 * result: ebInterface → UBL → ebInterface is lossless for every document in the corpus, while UBL →
 * ebInterface → UBL is not and cannot be, because ebInterface 6.1 has no element for a Peppol
 * endpoint. That deviation is asserted here, not just described in prose.
 */
class CrossFormatRoundTripTest {

  private static final EbInterface61Strategy EBI = new EbInterface61Strategy();
  private static final Ubl21InvoiceStrategy UBL_INVOICES = new Ubl21InvoiceStrategy();
  private static final Ubl21CreditNoteStrategy UBL_CREDIT_NOTES = new Ubl21CreditNoteStrategy();
  private static final InvoiceToUblMapper TO_UBL = new InvoiceToUblMapper();
  private static final UblToInvoiceMapper FROM_UBL = new UblToInvoiceMapper();
  private static final InvoiceToEbInterface61Mapper TO_EBI = new InvoiceToEbInterface61Mapper();
  private static final EbInterface61ToInvoiceMapper FROM_EBI = new EbInterface61ToInvoiceMapper();

  private final InvoiceValidator validator = new InvoiceValidator();

  /**
   * Every valid ebInterface document in the corpus returns byte-for-byte identical from a trip
   * through UBL.
   *
   * <p>The three cover the shapes that differ where it matters: a fully populated B2G invoice, a
   * tax-exempt invoice carrying a {@code VATEX} code, and a reverse-charge credit note (which
   * changes the UBL root element, the type code and the line quantity element).
   *
   * <p>On an intentional mapper change this fails and prints both documents; that is the signal to
   * decide whether the new deviation is wanted, and to record it here if so.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "corpus/valid/b2g-full.xml",
        "corpus/valid/exempt-invoice.xml",
        "corpus/valid/credit-memo-reverse-charge.xml"
      })
  void ebInterfaceSurvivesATripThroughUblUnchanged(String resource) throws IOException {
    String original = readResource(resource);

    assertThat(ebInterfaceThroughUbl(original.getBytes(StandardCharsets.UTF_8)))
        .withFailMessage(
            "%s did not survive ebInterface -> UBL -> ebInterface unchanged."
                + " If the change is intended, update this test and say why.",
            resource)
        .isEqualTo(normalise(original));
  }

  /** The same claim for the committed samples twin, the artifact the README points a reader at. */
  @Test
  void theCommittedSamplesTwinSurvivesATripThroughUblUnchanged() throws IOException {
    Path twin = Path.of("..", "samples", "invoice-b2g-sample.ebinterface.xml");
    String original = Files.readString(twin, StandardCharsets.UTF_8);

    assertThat(ebInterfaceThroughUbl(Files.readAllBytes(twin))).isEqualTo(normalise(original));
  }

  /**
   * A round trip must not silently change an amount. Asserted separately from the byte comparison
   * above so that a failure is unambiguous: identical bytes already imply identical totals, but if
   * the byte assertion is ever relaxed for a formatting reason, this must not be relaxed with it.
   */
  @Test
  void aRoundTripNeverReportsADeviationOnItsOwnOutput() throws IOException {
    List<Finding> notes = new ArrayList<>();
    ebInterfaceThroughUbl(
        readResource("corpus/valid/b2g-full.xml").getBytes(StandardCharsets.UTF_8), notes);

    assertThat(notes).extracting(Finding::ruleId).doesNotContain(ConversionNotes.CONV_04);
  }

  /**
   * The documented deviation, in the direction that genuinely has one.
   *
   * <p>A Peppol UBL invoice carries both parties' electronic addresses (BT-34/BT-49). ebInterface
   * 6.1 has no element for a network delivery address at all, so the trip out loses them and the
   * trip back cannot invent them — the returned document is a valid invoice that is no longer
   * Peppol-routable. This is reported as {@code CONV-01} on the way out rather than discovered by
   * the caller at an access point, and asserted here so the claim stays true.
   */
  @Test
  void ublToEbInterfaceAndBackLosesExactlyTheDocumentedFields() throws IOException {
    byte[] source =
        readResource("corpus/valid/peppol-ubl-invoice.xml").getBytes(StandardCharsets.UTF_8);
    List<Finding> notes = new ArrayList<>();

    CanonicalResult in = FROM_UBL.map(UBL_INVOICES.read(source).document());
    notes.addAll(in.notes());
    notes.addAll(ConversionLosses.writingTo(in.invoice(), TargetFormat.EBINTERFACE_61));
    assertThat(in.invoice().seller().electronicAddress()).isPresent();

    String ebi = EBI.write(TO_EBI.map(in.invoice()));
    CanonicalResult back = FROM_EBI.map(EBI.read(ebi.getBytes(StandardCharsets.UTF_8)).document());
    notes.addAll(back.notes());

    // The endpoint is gone, and was reported gone.
    assertThat(back.invoice().seller().electronicAddress()).isEmpty();
    assertThat(back.invoice().buyer().electronicAddress()).isEmpty();
    assertThat(notes)
        .filteredOn(note -> note.ruleId().equals(ConversionNotes.CONV_01))
        .anySatisfy(note -> assertThat(note.location()).contains("electronicAddress"));

    // Everything that pays the invoice is untouched.
    assertThat(back.invoice().totals()).isEqualTo(in.invoice().totals());
    assertThat(back.invoice().invoiceNumber()).isEqualTo(in.invoice().invoiceNumber());
    assertThat(back.invoice().paymentMeans()).isEqualTo(in.invoice().paymentMeans());
    assertThat(notes).extracting(Finding::ruleId).doesNotContain(ConversionNotes.CONV_04);

    // And the returned UBL is no longer Peppol-clean — precisely because of the lost endpoints,
    // which is the deviation this test exists to document.
    String returned = UBL_INVOICES.write(ublInvoiceOf(back.invoice()));
    assertThat(validator.validate(returned.getBytes(StandardCharsets.UTF_8)).isValid()).isFalse();
  }

  /** ebInterface bytes in, ebInterface bytes out, via UBL. Line endings normalised. */
  private static String ebInterfaceThroughUbl(byte[] source) {
    return ebInterfaceThroughUbl(source, new ArrayList<>());
  }

  private static String ebInterfaceThroughUbl(byte[] source, List<Finding> notes) {
    CanonicalResult out = FROM_EBI.map(EBI.read(source).document());
    notes.addAll(out.notes());

    UblDocument ublDocument = TO_UBL.map(out.invoice());
    String ublXml =
        switch (ublDocument) {
          case UblDocument.CommercialInvoice(var document) -> UBL_INVOICES.write(document);
          case UblDocument.CreditNote(var document) -> UBL_CREDIT_NOTES.write(document);
        };
    byte[] ubl = ublXml.getBytes(StandardCharsets.UTF_8);

    CanonicalResult back =
        switch (ublDocument) {
          case UblDocument.CommercialInvoice(var ignored) ->
              FROM_UBL.map(UBL_INVOICES.read(ubl).document());
          case UblDocument.CreditNote(var ignored) ->
              FROM_UBL.map(UBL_CREDIT_NOTES.read(ubl).document());
        };
    notes.addAll(back.notes());

    return normalise(EBI.write(TO_EBI.map(back.invoice())));
  }

  private static InvoiceType ublInvoiceOf(Invoice invoice) {
    return ((UblDocument.CommercialInvoice) TO_UBL.map(invoice)).document();
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in =
        CrossFormatRoundTripTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Resource not found: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String normalise(String xml) {
    return xml.replace("\r", "").stripTrailing();
  }
}
