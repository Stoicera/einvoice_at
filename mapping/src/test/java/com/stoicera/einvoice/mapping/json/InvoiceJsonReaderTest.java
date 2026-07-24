package com.stoicera.einvoice.mapping.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import com.stoicera.einvoice.mapping.Fixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Behavior of {@link InvoiceJsonReader} against the canonical-invoice JSON shape. Two exception
 * families are exercised throughout: {@link InvoiceJsonException} for JSON-shape problems the
 * reader itself owns (syntax, unknown properties, wrong node types, unmapped enum/date/currency
 * strings) and {@link InvariantViolationException}, which the reader lets pass through untouched
 * whenever a well-formed document describes a domain-invalid invoice.
 */
class InvoiceJsonReaderTest {

  /** A minimal but valid canonical invoice: none of the optional fields are present. */
  private static final String MINIMAL_JSON =
      """
      {
        "invoiceNumber": "2026-000001",
        "type": "INVOICE",
        "issueDate": "2026-07-24",
        "currency": "EUR",
        "seller": { "name": "Seller GmbH", "vatId": "ATU11111111",
          "address": { "street": "Seller Str 1", "city": "Wien", "postalCode": "1010", "countryCode": "AT" } },
        "buyer": { "name": "Buyer GmbH", "vatId": "ATU22222222",
          "address": { "street": "Buyer Str 1", "city": "Wien", "postalCode": "1020", "countryCode": "AT" } },
        "lines": [
          { "id": "1", "description": "Beratung", "quantity": "1", "unitCode": "HUR", "unitPrice": "100.00", "vatCategory": "STANDARD", "vatPercent": "20" }
        ]
      }
      """;

  private final InvoiceJsonReader reader = new InvoiceJsonReader();

  // --- Happy path ------------------------------------------------------------------------------

  @Test
  void parsesSampleFileIntoExpectedInvoice() throws IOException {
    // surefire's working directory is the module directory (mapping/), so ".." reaches the repo
    // root and from there the repo-level samples/ directory.
    Path sample = Path.of("..", "samples", "invoice-b2g-sample.json");

    Invoice invoice;
    try (InputStream in = Files.newInputStream(sample)) {
      invoice = reader.read(in);
    }

    assertThat(invoice).isEqualTo(Fixtures.jsonSampleB2gInvoice());
  }

  @Test
  void parsesMinimalInvoiceWithoutOptionalFields() {
    Invoice invoice = reader.read(toStream(MINIMAL_JSON));

    Invoice expected =
        Invoice.builder()
            .invoiceNumber("2026-000001")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 24))
            .currency(Money.EUR)
            .seller(
                new Party(
                    "Seller GmbH",
                    new Address("Seller Str 1", "Wien", "1010", "AT"),
                    "ATU11111111"))
            .buyer(
                new Party(
                    "Buyer GmbH", new Address("Buyer Str 1", "Wien", "1020", "AT"), "ATU22222222"))
            .addLine(
                new InvoiceLine(
                    "1",
                    "Beratung",
                    new BigDecimal("1"),
                    "HUR",
                    new BigDecimal("100.00"),
                    VatRate.STANDARD_20))
            .build();
    assertThat(invoice).isEqualTo(expected);
  }

  @Test
  void parsesCreditNoteTypeToCreditNoteTypeCode() {
    String json = MINIMAL_JSON.replace("\"type\": \"INVOICE\"", "\"type\": \"CREDIT_NOTE\"");

    Invoice invoice = reader.read(toStream(json));

    assertThat(invoice.type()).isEqualTo(InvoiceTypeCode.CREDIT_NOTE);
  }

  @Test
  void wiresExemptionReasonsToTheBuilder() {
    String json =
        """
        {
          "invoiceNumber": "2026-EX-0001",
          "type": "INVOICE",
          "issueDate": "2026-07-24",
          "currency": "EUR",
          "seller": { "name": "Seller GmbH", "vatId": "ATU11111111",
            "address": { "street": "Seller Str 1", "city": "Wien", "postalCode": "1010", "countryCode": "AT" } },
          "buyer": { "name": "Buyer GmbH", "vatId": "ATU22222222",
            "address": { "street": "Buyer Str 1", "city": "Wien", "postalCode": "1020", "countryCode": "AT" } },
          "lines": [
            { "id": "1", "description": "Lieferung", "quantity": "1", "unitCode": "C62", "unitPrice": "500.00", "vatCategory": "EXEMPT", "vatPercent": "0" }
          ],
          "exemptionReasons": [
            { "category": "EXEMPT", "code": "VATEX-EU-G", "text": "Innergemeinschaftliche Lieferung" }
          ]
        }
        """;

    Invoice invoice = reader.read(toStream(json));

    assertThat(invoice.vatBreakdown()).hasSize(1);
    assertThat(invoice.vatBreakdown().get(0).exemptionReason())
        .isEqualTo(new VatExemptionReason("VATEX-EU-G", "Innergemeinschaftliche Lieferung"));
  }

  // --- JSON-shape errors -> InvoiceJsonException ------------------------------------------------

  @Test
  void unknownPropertyThrowsInvoiceJsonExceptionNamingTheProperty() {
    String json =
        MINIMAL_JSON.replace(
            "\"invoiceNumber\": \"2026-000001\",",
            "\"invoiceNumber\": \"2026-000001\", \"unexpectedField\": \"x\",");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessage("Unknown property 'unexpectedField' at unexpectedField");
  }

  @Test
  void numericUnitPriceThrowsInvoiceJsonExceptionNamingTheField() {
    String json = MINIMAL_JSON.replace("\"unitPrice\": \"100.00\"", "\"unitPrice\": 100.00");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("lines[0].unitPrice");
  }

  @Test
  void malformedJsonSyntaxThrowsInvoiceJsonException() {
    assertThatThrownBy(() -> reader.read(toStream("{not valid json")))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("Malformed JSON");
  }

  @Test
  void nullJsonBodyThrowsInvoiceJsonException() {
    assertThatThrownBy(() -> reader.read(toStream("null")))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("must not be null");
  }

  @Test
  void rootValueOfTheWrongShapeThrowsInvoiceJsonExceptionWithRootPath() {
    // A JSON string (not an object) at the root: the Jackson error path is empty here (the
    // mismatch is about the root value itself, not a named field), exercising fieldPath()'s
    // "<root>" fallback.
    assertThatThrownBy(() -> reader.read(toStream("\"just a string\"")))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("<root>");
  }

  @Test
  void ioFailureWhileReadingThrowsInvoiceJsonException() {
    InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("stream broke");
          }
        };

    assertThatThrownBy(() -> reader.read(broken))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("Failed to read JSON input");
  }

  @Test
  void unsupportedTypeValueThrowsInvoiceJsonException() {
    String json = MINIMAL_JSON.replace("\"type\": \"INVOICE\"", "\"type\": \"PROFORMA\"");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("type");
  }

  @Test
  void unsupportedVatCategoryValueThrowsInvoiceJsonException() {
    String json =
        MINIMAL_JSON.replace("\"vatCategory\": \"STANDARD\"", "\"vatCategory\": \"BOGUS\"");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("vatCategory");
  }

  @Test
  void invalidCurrencyCodeThrowsInvoiceJsonException() {
    String json = MINIMAL_JSON.replace("\"currency\": \"EUR\"", "\"currency\": \"NOTACODE\"");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void malformedIssueDateThrowsInvoiceJsonException() {
    String json =
        MINIMAL_JSON.replace("\"issueDate\": \"2026-07-24\"", "\"issueDate\": \"2026-13-40\"");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("issueDate");
  }

  @Test
  void malformedQuantityStringThrowsInvoiceJsonException() {
    String json = MINIMAL_JSON.replace("\"quantity\": \"1\"", "\"quantity\": \"abc\"");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void nullLineEntryThrowsInvoiceJsonException() {
    String json =
        MINIMAL_JSON.replace(
            """
            "lines": [
                { "id": "1", "description": "Beratung", "quantity": "1", "unitCode": "HUR", "unitPrice": "100.00", "vatCategory": "STANDARD", "vatPercent": "20" }
              ]""",
            "\"lines\": [ null ]");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("lines[]");
  }

  @Test
  void nullExemptionReasonEntryThrowsInvoiceJsonException() {
    // Appends the optional field by replacing MINIMAL_JSON's closing brace, rather than trying to
    // surgically match its tail formatting.
    String json =
        MINIMAL_JSON.substring(0, MINIMAL_JSON.lastIndexOf('}'))
            + ",\n  \"exemptionReasons\": [ null ]\n}\n";

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isInstanceOf(InvoiceJsonException.class)
        .hasMessageContaining("exemptionReasons[]");
  }

  // --- Domain invariants pass through untouched -> InvariantViolationException -----------------

  @Test
  void missingInvoiceNumberThrowsInvariantViolationException() {
    String json = MINIMAL_JSON.replace("\"invoiceNumber\": \"2026-000001\",", "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Invoice number");
  }

  @Test
  void missingTypeThrowsInvariantViolationException() {
    String json = MINIMAL_JSON.replace("\"type\": \"INVOICE\",", "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("type");
  }

  @Test
  void missingCurrencyThrowsInvariantViolationException() {
    String json = MINIMAL_JSON.replace("\"currency\": \"EUR\",", "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("currency");
  }

  @Test
  void missingIssueDateThrowsInvariantViolationException() {
    String json = MINIMAL_JSON.replace("\"issueDate\": \"2026-07-24\",", "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("issue date");
  }

  @Test
  void missingSellerThrowsInvariantViolationException() {
    String json =
        MINIMAL_JSON.replace(
            """
            "seller": { "name": "Seller GmbH", "vatId": "ATU11111111",
                "address": { "street": "Seller Str 1", "city": "Wien", "postalCode": "1010", "countryCode": "AT" } },
            """,
            "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("seller");
  }

  @Test
  void missingSellerAddressThrowsInvariantViolationException() {
    String json =
        MINIMAL_JSON.replace(
            """
            "address": { "street": "Seller Str 1", "city": "Wien", "postalCode": "1010", "countryCode": "AT" } },
            """,
            "\"address\": null },\n");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("address");
  }

  @Test
  void missingQuantityThrowsInvariantViolationException() {
    String json = MINIMAL_JSON.replace("\"quantity\": \"1\", ", "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void missingLinesThrowsInvariantViolationException() {
    // Regex removal (rather than a literal substring match) so this test stays robust to
    // MINIMAL_JSON's exact indentation; it also strips the trailing comma left dangling by the
    // preceding field.
    String json = MINIMAL_JSON.replaceAll("(?s),?\\s*\"lines\"\\s*:\\s*\\[.*?]", "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("at least one line");
  }

  @Test
  void missingVatCategoryThrowsInvariantViolationException() {
    String json = MINIMAL_JSON.replace("\"vatCategory\": \"STANDARD\", ", "");

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("VAT category");
  }

  @Test
  void invalidIbanChecksumThrowsInvariantViolationExceptionUnwrapped() {
    String json =
        MINIMAL_JSON.substring(0, MINIMAL_JSON.lastIndexOf('}'))
            + ",\n  \"paymentMeans\": { \"iban\": \"AT611904300234573202\" }\n}\n";

    assertThatThrownBy(() -> reader.read(toStream(json)))
        .isExactlyInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("checksum");
  }

  private static InputStream toStream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }
}
