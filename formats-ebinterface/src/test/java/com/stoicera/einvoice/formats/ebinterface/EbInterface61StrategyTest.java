package com.stoicera.einvoice.formats.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helger.ebinterface.v61.Ebi61BillerType;
import com.helger.ebinterface.v61.Ebi61InvoiceRecipientType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.stoicera.einvoice.formats.api.ReadResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class EbInterface61StrategyTest {

  private final EbInterface61Strategy strategy = new EbInterface61Strategy();

  /**
   * The smallest tree the round-trip needs. This is deliberately NOT schema-complete: the reader
   * and writer here are lenient (SPEC §10 — schema validation is the validation module's job), so a
   * bare invoice number, date and the two VAT-carrying parties are enough to prove read/write.
   */
  private static Ebi61InvoiceType minimalInvoice() {
    Ebi61InvoiceType invoice = new Ebi61InvoiceType();
    invoice.setInvoiceNumber("2026-0001");
    invoice.setInvoiceDate(LocalDate.of(2026, 7, 24));

    Ebi61BillerType biller = new Ebi61BillerType();
    biller.setVATIdentificationNumber("ATU12345678");
    invoice.setBiller(biller);

    Ebi61InvoiceRecipientType recipient = new Ebi61InvoiceRecipientType();
    recipient.setVATIdentificationNumber("ATU87654321");
    invoice.setInvoiceRecipient(recipient);
    return invoice;
  }

  @Test
  void namespaceUriIsEbInterface61() {
    assertThat(strategy.namespaceUri()).isEqualTo("http://www.ebinterface.at/schema/6p1/");
  }

  @Test
  void writeProducesFormattedXmlInTheEbInterface61Namespace() {
    String xml = strategy.write(minimalInvoice());

    assertThat(xml)
        .contains(strategy.namespaceUri())
        .contains("InvoiceNumber")
        .contains("2026-0001")
        .contains("\n"); // setFormattedOutput(true)
  }

  @Test
  void writeThenReadRoundTripsTheInvoiceNumber() {
    String xml = strategy.write(minimalInvoice());

    ReadResult<Ebi61InvoiceType> result = strategy.read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.errors()).isEmpty();
    assertThat(result.document()).isNotNull();
    assertThat(result.document().getInvoiceNumber()).isEqualTo("2026-0001");
  }

  @Test
  void readOfGarbageBytesFailsWithCollectedErrors() {
    ReadResult<Ebi61InvoiceType> result = strategy.read("not xml".getBytes(StandardCharsets.UTF_8));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
  }

  @Test
  void readOfWellFormedButWrongNamespaceXmlFails() {
    byte[] wrongNamespace =
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Invoice xmlns=\"urn:example:not-ebinterface\">"
                + "<InvoiceNumber>1</InvoiceNumber>"
                + "</Invoice>")
            .getBytes(StandardCharsets.UTF_8);

    ReadResult<Ebi61InvoiceType> result = strategy.read(wrongNamespace);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
  }

  /**
   * The write path's "null means the marshal failed" guard, pinned rather than assumed.
   *
   * <p>Tested through {@link EbInterface61Strategy#requireMarshalled(String)} rather than by
   * feeding {@code write} a tree that fails to marshal, because no such tree can be built:
   * ph-ebinterface's writer escapes or drops even characters that XML 1.0 cannot represent — a raw
   * {@code U+0000} and a lone surrogate were both tried, and both produced perfectly good XML. The
   * guard stays because {@code IJAXBWriter.getAsString} is declared nullable, so the library is
   * allowed to return null; this test pins what happens when it does.
   *
   * <p>The branch had been riding over the module's coverage threshold on {@code ReadResult}'s
   * numbers until that record moved to {@code formats-api} in M4, at which point the gate exposed
   * it. The gate doing its job; the fix is the missing test, never a lower threshold (CLAUDE.md).
   */
  @Test
  void writeRejectsAFailedMarshalInsteadOfReturningNull() {
    assertThatThrownBy(() -> EbInterface61Strategy.requireMarshalled(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be marshalled");

    assertThat(EbInterface61Strategy.requireMarshalled("<Invoice/>")).isEqualTo("<Invoice/>");
  }

  @Test
  void readFromDomRoundTripsTheInvoiceNumber() {
    Document dom = parse(strategy.write(minimalInvoice()));

    ReadResult<Ebi61InvoiceType> result = strategy.read(dom);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.errors()).isEmpty();
    assertThat(result.document()).isNotNull();
    assertThat(result.document().getInvoiceNumber()).isEqualTo("2026-0001");
  }

  @Test
  void readFromDomWithWrongNamespaceRootFails() {
    Document dom =
        parse(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Invoice xmlns=\"urn:example:not-ebinterface\">"
                + "<InvoiceNumber>1</InvoiceNumber>"
                + "</Invoice>");

    ReadResult<Ebi61InvoiceType> result = strategy.read(dom);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).isNotEmpty();
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
