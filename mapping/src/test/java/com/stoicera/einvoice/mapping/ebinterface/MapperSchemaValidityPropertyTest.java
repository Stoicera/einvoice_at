package com.stoicera.einvoice.mapping.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.list.ErrorList;
import com.helger.ebinterface.EbInterface61Marshaller;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property tests for {@link InvoiceToEbInterface61Mapper}. The load-bearing property is <em>schema
 * validity</em>: every canonical invoice the generator can produce must map to an ebInterface 6.1
 * document that re-reads without a single schema error through a schema-validating marshaller. Two
 * structural properties guard the parts the mapper must never drop or recompute: the tax-item count
 * and the payable amount.
 *
 * <p>The strategy's own writer is deliberately lenient (schema off); this test re-reads its output
 * with {@code setUseSchema(true)} so the bundled ebInterface 6.1 XSD is the judge, not the writer.
 */
class MapperSchemaValidityPropertyTest {

  private static final InvoiceToEbInterface61Mapper MAPPER = new InvoiceToEbInterface61Mapper();
  private static final EbInterface61Strategy STRATEGY = new EbInterface61Strategy();

  @Property(tries = 300)
  void mappedInvoiceReReadsWithoutSchemaErrors(@ForAll("canonicalInvoices") Invoice invoice) {
    String xml = STRATEGY.write(MAPPER.map(invoice));

    ErrorList errors = new ErrorList();
    Ebi61InvoiceType reread =
        new EbInterface61Marshaller()
            .setUseSchema(true)
            .setCollectErrors(errors)
            .read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", messages(errors), xml)
        .isFalse();
    assertThat(reread).isNotNull();
  }

  @Property(tries = 300)
  void taxItemCountEqualsCanonicalBreakdownSize(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);

    assertThat(ebi.getTax().getTaxItemCount()).isEqualTo(invoice.vatBreakdown().size());
  }

  @Property(tries = 300)
  void payableAmountEqualsCanonicalPayable(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);

    assertThat(ebi.getPayableAmount())
        .isEqualByComparingTo(invoice.totals().payableAmount().amount());
  }

  private static List<String> messages(ErrorList errors) {
    List<String> out = new ArrayList<>();
    for (IError error : errors) {
      out.add(error.getAsStringLocaleIndepdent());
    }
    return out;
  }

  // --- Generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<Invoice> canonicalInvoices() {
    return Combinators.combine(
            references(),
            Arbitraries.of(InvoiceTypeCode.values()),
            issueDates(),
            parties(),
            parties(),
            lines(),
            optionals())
        .as(MapperSchemaValidityPropertyTest::buildInvoice);
  }

  private static Invoice buildInvoice(
      String invoiceNumber,
      InvoiceTypeCode type,
      LocalDate issueDate,
      Party seller,
      Party buyer,
      List<LineData> lineData,
      Optionals optionals) {
    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber(invoiceNumber)
            .type(type)
            .issueDate(issueDate)
            .currency(Money.EUR)
            .seller(seller)
            .buyer(buyer);

    int position = 1;
    boolean hasExempt = false;
    for (LineData data : lineData) {
      builder.addLine(
          new InvoiceLine(
              String.valueOf(position++),
              data.description(),
              data.quantity(),
              data.unitCode(),
              data.unitPrice(),
              data.rate()));
      hasExempt |= data.rate().category() == VatCategory.EXEMPT;
    }
    if (hasExempt) {
      builder.exemptionReason(
          VatCategory.EXEMPT,
          new VatExemptionReason("VATEX-EU-G", "Innergemeinschaftliche Lieferung"));
    }

    if (optionals.orderReference() != null) {
      builder.orderReference(optionals.orderReference());
    }
    if (optionals.supplierNumber() != null) {
      builder.supplierNumber(optionals.supplierNumber());
    }
    if (optionals.paymentMeans() != null) {
      builder.paymentMeans(optionals.paymentMeans());
    }
    if (optionals.dueInDays() != null) {
      builder.dueDate(issueDate.plusDays(optionals.dueInDays()));
    }
    if (optionals.paymentTerms() != null) {
      builder.paymentTerms(optionals.paymentTerms());
    }
    return builder.build();
  }

  private Arbitrary<Optionals> optionals() {
    return Combinators.combine(
            references().injectNull(0.4),
            references().injectNull(0.4),
            paymentMeans().injectNull(0.4),
            Arbitraries.integers().between(0, 120).injectNull(0.4),
            safeText().injectNull(0.4))
        .as(Optionals::new);
  }

  private Arbitrary<List<LineData>> lines() {
    return lineData().list().ofMinSize(1).ofMaxSize(5);
  }

  private Arbitrary<LineData> lineData() {
    return Combinators.combine(
            safeText(),
            Arbitraries.integers().between(1, 100).map(BigDecimal::valueOf),
            Arbitraries.of("C62", "HUR", "KGM", "MTR", "LTR"),
            Arbitraries.integers().between(0, 200_000).map(c -> new BigDecimal(c).movePointLeft(2)),
            Arbitraries.of(
                VatRate.STANDARD_20,
                VatRate.REDUCED_13,
                VatRate.REDUCED_10,
                VatRate.ZERO,
                VatRate.REVERSE_CHARGE,
                VatRate.EXEMPT))
        .as(LineData::new);
  }

  private Arbitrary<Party> parties() {
    return Combinators.combine(names(), addresses(), vatIds())
        .as((name, address, vatId) -> new Party(name, address, vatId));
  }

  private Arbitrary<Address> addresses() {
    return Combinators.combine(
            names(),
            names(),
            Arbitraries.strings().withCharRange('0', '9').ofLength(4),
            Arbitraries.of("AT", "DE", "IT"))
        .as(Address::new);
  }

  private Arbitrary<PaymentMeans> paymentMeans() {
    Arbitrary<Iban> ibans =
        Arbitraries.of(
                "AT611904300234573201",
                "DE89370400440532013000",
                "NL91ABNA0417164300",
                "GB82WEST12345698765432")
            .map(Iban::new);
    Arbitrary<String> bics = Arbitraries.of("BKAUATWW", "GIBAATWWXXX", "RLNWATWW").injectNull(0.4);
    return Combinators.combine(ibans, bics).as(PaymentMeans::new);
  }

  /**
   * VAT ids, with a null arm: core permits {@code Party.vatId == null} (Kleinunternehmer/private
   * buyer), and the mapper must map that state to schema-valid XML via the {@code ATU00000000}
   * convention. Injecting null here keeps the schema-validity property honest about the model's
   * full state space (finding A1).
   */
  private Arbitrary<String> vatIds() {
    return Arbitraries.strings()
        .withCharRange('0', '9')
        .ofLength(8)
        .map(digits -> "ATU" + digits)
        .injectNull(0.2);
  }

  /** Bounded date range so {@code plusDays} for the due date can never overflow. */
  private Arbitrary<LocalDate> issueDates() {
    return Arbitraries.integers()
        .between(0, 7000)
        .map(offset -> LocalDate.of(2015, 1, 1).plusDays(offset));
  }

  /** German letters incl. umlauts, never blank (no whitespace in the charset). */
  private Arbitrary<String> names() {
    return Arbitraries.strings()
        .withChars("abcdefghijklmnopqrstuvwxyzäöüßÄÖÜ")
        .ofMinLength(3)
        .ofMaxLength(20);
  }

  private Arbitrary<String> references() {
    return Arbitraries.strings()
        .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-")
        .ofMinLength(3)
        .ofMaxLength(15);
  }

  private Arbitrary<String> safeText() {
    return Arbitraries.strings()
        .withChars("abcdefghijklmnopqrstuvwxyzäöüß .,%-")
        .ofMinLength(3)
        .ofMaxLength(40)
        .map(text -> "Zahlung " + text);
  }

  private record LineData(
      String description,
      BigDecimal quantity,
      String unitCode,
      BigDecimal unitPrice,
      VatRate rate) {}

  private record Optionals(
      String orderReference,
      String supplierNumber,
      PaymentMeans paymentMeans,
      Integer dueInDays,
      String paymentTerms) {}
}
