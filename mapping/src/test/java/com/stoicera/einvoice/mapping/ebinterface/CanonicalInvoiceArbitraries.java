package com.stoicera.einvoice.mapping.ebinterface;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Shared jqwik generators for canonical {@link Invoice}s used by the mapper property tests. Kept in
 * one place so the schema-validity property ({@link MapperSchemaValidityPropertyTest}) and the
 * value-preservation properties ({@link MapperValuePreservationPropertyTest}) exercise the
 * <em>same</em> input space — a single source of truth rather than two drift-prone copies.
 *
 * <p>The space is deliberately wide (finding C2): quantities and unit prices come in both scale-0/2
 * and scale-4 arms; every {@link VatRate} in the Austrian set is drawn (including reverse charge
 * and exempt), with a caller-supplied exemption reason wired for AE and E; and {@code vatId}
 * carries a null arm (finding A1) so the no-UID convention is covered. Magnitudes stay well inside
 * the core integer-digit caps so a generated invoice is always constructible and always maps to
 * schema-valid ebInterface 6.1.
 *
 * <p>M3 Task 2 added two further arms: each party independently carries an absent-or-present {@code
 * email} ({@link #parties()}), and the invoice as a whole draws one of the three legal delivery
 * states — neither, a {@code deliveryDate}, or a {@code servicePeriod} ({@link #deliveryArms()},
 * mirroring {@code core}'s own {@code Generators.deliveryArms()} so the two test suites cover the
 * same mutual-exclusion invariant without generating the illegal "both present" combination).
 */
final class CanonicalInvoiceArbitraries {

  private CanonicalInvoiceArbitraries() {}

  static Arbitrary<Invoice> canonicalInvoices() {
    return Combinators.combine(
            references(),
            Arbitraries.of(InvoiceTypeCode.values()),
            issueDates(),
            parties(),
            parties(),
            lines(),
            optionals())
        .as(CanonicalInvoiceArbitraries::buildInvoice);
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
    boolean hasReverseCharge = false;
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
      hasReverseCharge |= data.rate().category() == VatCategory.REVERSE_CHARGE;
    }
    if (hasExempt) {
      builder.exemptionReason(
          VatCategory.EXEMPT,
          new VatExemptionReason("VATEX-EU-G", "Innergemeinschaftliche Lieferung"));
    }
    if (hasReverseCharge) {
      // A caller-supplied (custom) AE reason, distinct from the builder's BR-AE-10 default, so the
      // AE exemption-comment path is exercised with a non-default reason (finding C2).
      builder.exemptionReason(
          VatCategory.REVERSE_CHARGE,
          new VatExemptionReason("VATEX-EU-AE", "Übergang der Steuerschuld gem. § 19 Abs 1 UStG"));
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
    optionals.deliveryArm().deliveryDate().ifPresent(builder::deliveryDate);
    optionals.deliveryArm().servicePeriod().ifPresent(builder::servicePeriod);
    return builder.build();
  }

  private static Arbitrary<Optionals> optionals() {
    return Combinators.combine(
            references().injectNull(0.4),
            references().injectNull(0.4),
            paymentMeans().injectNull(0.4),
            Arbitraries.integers().between(0, 120).injectNull(0.4),
            safeText().injectNull(0.4),
            deliveryArms())
        .as(Optionals::new);
  }

  /**
   * Bounded date range so a generated delivery date/service-period start never drifts far from the
   * fixed {@link #issueDates()} range this suite uses elsewhere.
   */
  private static Arbitrary<LocalDate> deliveryDates() {
    return Arbitraries.integers()
        .between(0, 7000)
        .map(offset -> LocalDate.of(2015, 1, 1).plusDays(offset));
  }

  private static Arbitrary<ServicePeriod> servicePeriods() {
    return Combinators.combine(deliveryDates(), Arbitraries.integers().between(0, 90))
        .as((from, spanDays) -> new ServicePeriod(from, from.plusDays(spanDays)));
  }

  /**
   * Delivery date and service period are mutually exclusive on {@link Invoice} (§ 11 Abs 1 Z 4
   * UStG); this arbitrary picks one of the three legal arms — neither, a delivery date, or a
   * service period — rather than generating the two independently, which could accidentally produce
   * the illegal "both present" combination.
   */
  private static Arbitrary<DeliveryArm> deliveryArms() {
    Arbitrary<DeliveryArm> none =
        Arbitraries.just(new DeliveryArm(Optional.empty(), Optional.empty()));
    Arbitrary<DeliveryArm> dateOnly =
        deliveryDates().map(date -> new DeliveryArm(Optional.of(date), Optional.empty()));
    Arbitrary<DeliveryArm> periodOnly =
        servicePeriods().map(period -> new DeliveryArm(Optional.empty(), Optional.of(period)));
    return Arbitraries.oneOf(none, dateOnly, periodOnly);
  }

  private static Arbitrary<String> emails() {
    Arbitrary<String> token =
        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    Arbitrary<String> tld = Arbitraries.of("at", "com", "org", "eu");
    return Combinators.combine(token, token, tld)
        .as((local, domain, t) -> local + "@" + domain + "." + t);
  }

  /** Absent or present arm for {@link Party#email()}. */
  private static Arbitrary<Optional<String>> optionalEmails() {
    Arbitrary<Optional<String>> absent = Arbitraries.just(Optional.empty());
    Arbitrary<Optional<String>> present = emails().map(Optional::of);
    return Arbitraries.oneOf(absent, present);
  }

  private static Arbitrary<List<LineData>> lines() {
    return lineData().list().ofMinSize(1).ofMaxSize(5);
  }

  private static Arbitrary<LineData> lineData() {
    return Combinators.combine(safeText(), quantities(), unitCodes(), unitPrices(), rates())
        .as(LineData::new);
  }

  /**
   * Line quantities in two arms: positive integers (scale 0) and scale-4 fractional quantities
   * (finding C2 — the mapper's copy of a scale-4 quantity into {@code Ebi61UnitType} was otherwise
   * never generated). Both stay non-zero and well within the core 7-integer-digit cap.
   */
  private static Arbitrary<BigDecimal> quantities() {
    Arbitrary<BigDecimal> scale0 = Arbitraries.integers().between(1, 100).map(BigDecimal::valueOf);
    Arbitrary<BigDecimal> scale4 =
        Arbitraries.longs().between(1, 9_999_999).map(v -> new BigDecimal(v).movePointLeft(4));
    return Arbitraries.oneOf(scale0, scale4);
  }

  /**
   * Unit prices in two arms: scale-2 (≤ 2000.00) and scale-4 fractional prices (finding C2). Both
   * are non-negative (BR-27) and well within the core 8-integer-digit cap.
   */
  private static Arbitrary<BigDecimal> unitPrices() {
    Arbitrary<BigDecimal> scale2 =
        Arbitraries.integers().between(0, 200_000).map(c -> new BigDecimal(c).movePointLeft(2));
    Arbitrary<BigDecimal> scale4 =
        Arbitraries.longs().between(0, 99_999_999).map(v -> new BigDecimal(v).movePointLeft(4));
    return Arbitraries.oneOf(scale2, scale4);
  }

  private static Arbitrary<String> unitCodes() {
    return Arbitraries.of("C62", "HUR", "KGM", "MTR", "LTR");
  }

  private static Arbitrary<VatRate> rates() {
    return Arbitraries.of(
        VatRate.STANDARD_20,
        VatRate.REDUCED_13,
        VatRate.REDUCED_10,
        VatRate.ZERO,
        VatRate.REVERSE_CHARGE,
        VatRate.EXEMPT);
  }

  private static Arbitrary<Party> parties() {
    return Combinators.combine(names(), addresses(), vatIds(), optionalEmails()).as(Party::new);
  }

  private static Arbitrary<Address> addresses() {
    return Combinators.combine(
            names(),
            names(),
            Arbitraries.strings().withCharRange('0', '9').ofLength(4),
            Arbitraries.of("AT", "DE", "IT"))
        .as(Address::new);
  }

  private static Arbitrary<PaymentMeans> paymentMeans() {
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
   * convention. Injecting null here keeps the property suite honest about the model's full state
   * space (finding A1).
   */
  private static Arbitrary<String> vatIds() {
    return Arbitraries.strings()
        .withCharRange('0', '9')
        .ofLength(8)
        .map(digits -> "ATU" + digits)
        .injectNull(0.2);
  }

  /** Bounded date range so {@code plusDays} for the due date can never overflow. */
  private static Arbitrary<LocalDate> issueDates() {
    return Arbitraries.integers()
        .between(0, 7000)
        .map(offset -> LocalDate.of(2015, 1, 1).plusDays(offset));
  }

  /** German letters incl. umlauts, never blank (no whitespace in the charset). */
  private static Arbitrary<String> names() {
    return Arbitraries.strings()
        .withChars("abcdefghijklmnopqrstuvwxyzäöüßÄÖÜ")
        .ofMinLength(3)
        .ofMaxLength(20);
  }

  private static Arbitrary<String> references() {
    return Arbitraries.strings()
        .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-")
        .ofMinLength(3)
        .ofMaxLength(15);
  }

  private static Arbitrary<String> safeText() {
    return Arbitraries.strings()
        .withChars("abcdefghijklmnopqrstuvwxyzäöüß .,%-")
        .ofMinLength(3)
        .ofMaxLength(40)
        .map(text -> "Zahlung " + text);
  }

  record LineData(
      String description,
      BigDecimal quantity,
      String unitCode,
      BigDecimal unitPrice,
      VatRate rate) {}

  record Optionals(
      String orderReference,
      String supplierNumber,
      PaymentMeans paymentMeans,
      Integer dueInDays,
      String paymentTerms,
      DeliveryArm deliveryArm) {}

  /** One of the three legal delivery-info arms; see {@link #deliveryArms()}. */
  record DeliveryArm(Optional<LocalDate> deliveryDate, Optional<ServicePeriod> servicePeriod) {}
}
