package com.stoicera.einvoice.formats.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.ebinterface.v61.Ebi61BillerType;
import com.helger.ebinterface.v61.Ebi61InvoiceRecipientType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

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
}
