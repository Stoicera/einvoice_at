package com.stoicera.einvoice.formats.ubl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.formats.api.ReadResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.IDType;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * Both UBL billing strategies, tested through the one shared body they run on.
 *
 * <p>The fixtures are deliberately NOT schema-complete: this adapter is lenient by contract (SPEC
 * §10 — schema and Schematron validation are the validation module's job), so a bare document id is
 * enough to prove read/write, and the cross-kind cases below matter far more than a fully populated
 * tree would.
 */
class Ubl21StrategyTest {

  private final Ubl21InvoiceStrategy invoices = new Ubl21InvoiceStrategy();
  private final Ubl21CreditNoteStrategy creditNotes = new Ubl21CreditNoteStrategy();

  private static InvoiceType minimalInvoice() {
    InvoiceType invoice = new InvoiceType();
    invoice.setID(id("2026-0001"));
    return invoice;
  }

  private static CreditNoteType minimalCreditNote() {
    CreditNoteType creditNote = new CreditNoteType();
    creditNote.setID(id("2026-0002"));
    return creditNote;
  }

  private static IDType id(String value) {
    IDType idType = new IDType();
    idType.setValue(value);
    return idType;
  }

  @Test
  void namespacesComeFromPhUblAndDifferPerDocumentKind() {
    assertThat(invoices.namespaceUri())
        .isEqualTo("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2");
    assertThat(creditNotes.namespaceUri())
        .isEqualTo("urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2");
    assertThat(invoices.namespaceUri()).isNotEqualTo(creditNotes.namespaceUri());

    assertThat(invoices.documentKind()).isEqualTo(UblDocumentKind.INVOICE);
    assertThat(creditNotes.documentKind()).isEqualTo(UblDocumentKind.CREDIT_NOTE);
  }

  @Test
  void writeProducesFormattedXmlWithReadableUblPrefixes() {
    String xml = invoices.write(minimalInvoice());

    assertThat(xml)
        .contains(invoices.namespaceUri())
        .contains("2026-0001")
        .contains("\n"); // setFormattedOutput(true)
    // ph-ubl installs its own namespace context, so the output carries the conventional cbc:/cac:
    // prefixes rather than JAXB's generated ns0/ns1. This is cosmetic for a parser and decisive for
    // a human reading a converted document, so it is pinned rather than assumed.
    assertThat(xml).contains("cbc:ID");
  }

  @Test
  void writeThenReadRoundTripsAnInvoice() {
    String xml = invoices.write(minimalInvoice());

    ReadResult<InvoiceType> result = invoices.read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.errors()).isEmpty();
    assertThat(result.document()).isNotNull();
    assertThat(result.document().getIDValue()).isEqualTo("2026-0001");
  }

  @Test
  void writeThenReadRoundTripsACreditNote() {
    String xml = creditNotes.write(minimalCreditNote());

    ReadResult<CreditNoteType> result = creditNotes.read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.errors()).isEmpty();
    assertThat(result.document()).isNotNull();
    assertThat(result.document().getIDValue()).isEqualTo("2026-0002");
  }

  /**
   * The cross-kind case UBL makes possible and ebInterface does not: a credit note is a different
   * root element, so handing one to the invoice strategy must fail rather than yield a hollow tree.
   * This is what lets the caller trust {@code documentKind()} instead of re-checking the root
   * element itself.
   */
  @Test
  void readingACreditNoteAsAnInvoiceFails() {
    String creditNoteXml = creditNotes.write(minimalCreditNote());

    ReadResult<InvoiceType> result = invoices.read(creditNoteXml.getBytes(StandardCharsets.UTF_8));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
  }

  @Test
  void readingAnInvoiceAsACreditNoteFails() {
    String invoiceXml = invoices.write(minimalInvoice());

    ReadResult<CreditNoteType> result =
        creditNotes.read(invoiceXml.getBytes(StandardCharsets.UTF_8));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
  }

  @Test
  void readOfGarbageBytesFailsWithCollectedErrors() {
    ReadResult<InvoiceType> result = invoices.read("not xml".getBytes(StandardCharsets.UTF_8));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
  }

  @Test
  void readOfWellFormedButForeignNamespaceXmlFails() {
    byte[] foreign =
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Invoice xmlns=\"urn:example:not-ubl\"><ID>1</ID></Invoice>")
            .getBytes(StandardCharsets.UTF_8);

    ReadResult<InvoiceType> result = invoices.read(foreign);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
  }

  @Test
  void readFromDomRoundTripsAnInvoice() {
    Document dom = parse(invoices.write(minimalInvoice()));

    ReadResult<InvoiceType> result = invoices.read(dom);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.errors()).isEmpty();
    assertThat(result.document()).isNotNull();
    assertThat(result.document().getIDValue()).isEqualTo("2026-0001");
  }

  @Test
  void readFromDomWithForeignRootFails() {
    Document dom =
        parse(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Invoice xmlns=\"urn:example:not-ubl\"><ID>1</ID></Invoice>");

    ReadResult<InvoiceType> result = invoices.read(dom);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
  }

  /**
   * The write path's "null means the marshal failed" guard, pinned rather than assumed — the same
   * shape and the same reasoning as {@code EbInterface61StrategyTest}'s. {@code
   * IJAXBWriter.getAsString} is declared nullable, so the library is allowed to return null; no
   * legitimately constructible tree provokes it, so the policy is tested directly.
   */
  @Test
  void writeRejectsAFailedMarshalInsteadOfReturningNull() {
    assertThatThrownBy(() -> AbstractUbl21Strategy.requireMarshalled(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be marshalled");

    assertThat(AbstractUbl21Strategy.requireMarshalled("<Invoice/>")).isEqualTo("<Invoice/>");
  }

  /**
   * Parses {@code xml} into a namespace-aware DOM the way the validation module's SecureXml does.
   */
  private static Document parse(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory
          .newDocumentBuilder()
          .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("test fixture DOM did not parse", e);
    }
  }
}
