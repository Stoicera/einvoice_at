package com.stoicera.einvoice.mapping.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.ElectronicAddress;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConversionLossesTest {

  private static final Address LINZ = new Address("Hauptplatz 1", "Linz", "4020", "AT");

  @Test
  void aPlainInvoiceLosesNothingWritingToEitherFormat() {
    Invoice invoice = build(builder -> {});

    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.EBINTERFACE_61)).isEmpty();
    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.UBL)).isEmpty();
  }

  @Test
  void ebInterfaceLosesTheElectronicAddress() {
    Invoice invoice =
        build(
            builder ->
                builder.seller(
                    new Party(
                        "Verkäufer GmbH",
                        LINZ,
                        "ATU12345678",
                        Optional.empty(),
                        Optional.of(new ElectronicAddress("9915", "ATU12345678")))));

    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.EBINTERFACE_61))
        .singleElement()
        .satisfies(
            note -> {
              assertThat(note.ruleId()).isEqualTo(ConversionNotes.CONV_01);
              assertThat(note.severity()).isEqualTo(Severity.WARN);
              assertThat(note.messageDe()).contains("BT-34/BT-49");
            });

    // UBL is exactly the format that can carry it, so nothing is reported there.
    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.UBL)).isEmpty();
  }

  @Test
  void ebInterfaceReportsTheExemptionCodeBeingMergedIntoFreeText() {
    Invoice invoice = reverseChargeInvoice();

    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.EBINTERFACE_61))
        .anySatisfy(
            note -> {
              assertThat(note.ruleId()).isEqualTo(ConversionNotes.CONV_03);
              assertThat(note.severity()).isEqualTo(Severity.INFO);
              assertThat(note.messageDe()).contains("BT-121");
            });
    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.UBL)).isEmpty();
  }

  @Test
  void ebInterfaceReportsNonOrdinalLineIdsBeingRenumbered() {
    Invoice invoice =
        build(
            builder ->
                builder.addLine(
                    new InvoiceLine(
                        "POS-4711",
                        "Zusatzleistung",
                        new BigDecimal("1"),
                        "C62",
                        new BigDecimal("10.00"),
                        VatRate.STANDARD_20)));

    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.EBINTERFACE_61))
        .extracting(Finding::messageDe)
        .anySatisfy(message -> assertThat(message).contains("Zeilen-IDs"));
  }

  /** Ordinal ids survive as position numbers, so reporting a loss would be noise. */
  @Test
  void ebInterfaceStaysSilentWhenLineIdsAreAlreadyOrdinals() {
    Invoice invoice = build(builder -> {});

    assertThat(invoice.lines().getFirst().id()).isEqualTo("1");
    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.EBINTERFACE_61)).isEmpty();
  }

  /**
   * UBL's one gap, and it is UBL's own: a credit note has no DueDate element, so BT-9 rides on the
   * payment means — which this credit note does not have.
   */
  @Test
  void ublLosesACreditNoteDueDateWhenThereAreNoPaymentMeans() {
    Invoice creditNote =
        build(
            builder ->
                builder.type(InvoiceTypeCode.CREDIT_NOTE).dueDate(LocalDate.of(2026, 7, 31)));

    assertThat(ConversionLosses.writingTo(creditNote, TargetFormat.UBL))
        .singleElement()
        .satisfies(
            note -> {
              assertThat(note.ruleId()).isEqualTo(ConversionNotes.CONV_01);
              assertThat(note.messageDe()).contains("BT-9");
            });
  }

  @Test
  void ublKeepsACreditNoteDueDateWhenPaymentMeansCanCarryIt() {
    Invoice creditNote =
        build(
            builder ->
                builder
                    .type(InvoiceTypeCode.CREDIT_NOTE)
                    .dueDate(LocalDate.of(2026, 7, 31))
                    .paymentMeans(new PaymentMeans(new Iban("AT611904300234573201"), null)));

    assertThat(ConversionLosses.writingTo(creditNote, TargetFormat.UBL)).isEmpty();
  }

  /** An ordinary invoice keeps its due date in cbc:DueDate; the credit-note gap does not apply. */
  @Test
  void ublKeepsAnOrdinaryInvoiceDueDateWithoutPaymentMeans() {
    Invoice invoice = build(builder -> builder.dueDate(LocalDate.of(2026, 7, 31)));

    assertThat(ConversionLosses.writingTo(invoice, TargetFormat.UBL)).isEmpty();
  }

  @Test
  void everyNoteIsGermanFirstAndCarriesNoUnsubstitutedPlaceholder() {
    List<Finding> notes =
        List.copyOf(
            ConversionLosses.writingTo(reverseChargeInvoice(), TargetFormat.EBINTERFACE_61));

    assertThat(notes).isNotEmpty();
    assertThat(notes)
        .allSatisfy(
            note -> {
              assertThat(note.messageDe()).isNotBlank().doesNotContain("%s");
              assertThat(note.messageEn()).isNotBlank().doesNotContain("%s");
            });
  }

  private static Invoice reverseChargeInvoice() {
    return Invoice.builder()
        .invoiceNumber("2026-000900")
        .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
        .issueDate(LocalDate.of(2026, 7, 1))
        .currency(Money.EUR)
        .seller(new Party("Ausführer GmbH", LINZ, "ATU12345678"))
        .buyer(new Party("Empfänger BV", LINZ, "NL123456789B01"))
        .addLine(
            new InvoiceLine(
                "1",
                "Bauleistung",
                new BigDecimal("1"),
                "C62",
                new BigDecimal("1000.00"),
                VatRate.REVERSE_CHARGE))
        .exemptionReason(
            VatCategory.REVERSE_CHARGE,
            new VatExemptionReason("VATEX-EU-AE", "Übergang der Steuerschuld"))
        .build();
  }

  private static Invoice build(Consumer<Invoice.Builder> customise) {
    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber("2026-000123")
            .type(InvoiceTypeCode.COMMERCIAL_INVOICE)
            .issueDate(LocalDate.of(2026, 7, 1))
            .currency(Money.EUR)
            .seller(new Party("Verkäufer GmbH", LINZ, "ATU12345678"))
            .buyer(new Party("Käufer GmbH", LINZ, "ATU87654321"))
            .addLine(
                new InvoiceLine(
                    "1",
                    "Leistung",
                    new BigDecimal("1"),
                    "C62",
                    new BigDecimal("100.00"),
                    VatRate.STANDARD_20));
    customise.accept(builder);
    return builder.build();
  }
}
