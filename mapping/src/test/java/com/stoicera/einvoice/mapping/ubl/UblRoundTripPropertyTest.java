package com.stoicera.einvoice.mapping.ubl;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.mapping.CanonicalInvoiceArbitraries;
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Round-trip properties for canonical → UBL 2.1 → canonical.
 *
 * <p>UBL round-trips more faithfully than ebInterface does, and the difference is informative
 * rather than incidental: UBL has an element for nearly everything EN 16931 defines, so line ids,
 * exemption codes and electronic addresses all survive here where the ebInterface trip loses them.
 * That is why the conversion report matters — the same canonical invoice loses different things
 * depending on where it is going.
 *
 * <p>One asymmetry remains, and it is UBL's, not ours: a credit note has no {@code cbc:DueDate}
 * element, so BT-9 rides on the payment means. A credit note with a due date and no payment means
 * therefore has nowhere to carry it.
 */
class UblRoundTripPropertyTest {

  private static final InvoiceToUblMapper FORWARD = new InvoiceToUblMapper();
  private static final UblToInvoiceMapper REVERSE = new UblToInvoiceMapper();

  @Property(tries = 200)
  void headerAndPartyFieldsSurviveTheRoundTrip(@ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    assertThat(roundTripped.invoiceNumber()).isEqualTo(original.invoiceNumber());
    assertThat(roundTripped.type()).isEqualTo(original.type());
    assertThat(roundTripped.issueDate()).isEqualTo(original.issueDate());
    assertThat(roundTripped.currency()).isEqualTo(original.currency());
    assertThat(roundTripped.orderReference()).isEqualTo(original.orderReference());
    assertThat(roundTripped.supplierNumber()).isEqualTo(original.supplierNumber());
    assertThat(roundTripped.paymentTerms()).isEqualTo(original.paymentTerms());
    assertThat(roundTripped.deliveryDate()).isEqualTo(original.deliveryDate());
    assertThat(roundTripped.servicePeriod()).isEqualTo(original.servicePeriod());
    assertThat(roundTripped.paymentMeans()).isEqualTo(original.paymentMeans());
  }

  /**
   * The whole party survives, electronic address included — the field ebInterface cannot carry at
   * all.
   */
  @Property(tries = 200)
  void partiesSurviveWholeIncludingTheElectronicAddress(
      @ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    assertThat(roundTripped.seller()).isEqualTo(original.seller());
    assertThat(roundTripped.buyer()).isEqualTo(original.buyer());
  }

  /** Line ids survive too: UBL identifies a line by {@code cbc:ID}, not by ordinal position. */
  @Property(tries = 200)
  void linesSurviveWholeIncludingTheirIds(@ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    assertThat(roundTripped.lines()).hasSameSizeAs(original.lines());
    for (int i = 0; i < original.lines().size(); i++) {
      InvoiceLine before = original.lines().get(i);
      InvoiceLine after = roundTripped.lines().get(i);

      assertThat(after.id()).isEqualTo(before.id());
      assertThat(after.description()).isEqualTo(before.description());
      assertThat(after.quantity()).isEqualByComparingTo(before.quantity());
      assertThat(after.unitCode()).isEqualTo(before.unitCode());
      assertThat(after.unitPrice()).isEqualByComparingTo(before.unitPrice());
      assertThat(after.vatRate()).isEqualTo(before.vatRate());
    }
  }

  @Property(tries = 200)
  void totalsAndBreakdownAreUnchanged(@ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    assertThat(roundTripped.totals()).isEqualTo(original.totals());
    assertThat(roundTripped.vatBreakdown()).isEqualTo(original.vatBreakdown());
  }

  /**
   * The exemption reason survives whole — code <em>and</em> text — because UBL has a dedicated
   * element for each. The ebInterface round trip cannot say this, which is exactly the kind of
   * difference the conversion report exists to surface.
   */
  @Property(tries = 200)
  void exemptionReasonsSurviveWithBothCodeAndText(@ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    for (int i = 0; i < original.vatBreakdown().size(); i++) {
      assertThat(roundTripped.vatBreakdown().get(i).exemptionReason())
          .isEqualTo(original.vatBreakdown().get(i).exemptionReason());
    }
  }

  @Property(tries = 200)
  void ourOwnOutputNeverDeviatesOnTotals(@ForAll("canonicalInvoices") Invoice original) {
    assertThat(roundTrip(original).notes()).extracting(Finding::ruleId).doesNotContain("CONV-04");
  }

  @Property(tries = 200)
  void noNoteLeaksAnUnsubstitutedFormatSpecifier(@ForAll("canonicalInvoices") Invoice original) {
    for (Finding note : roundTrip(original).notes()) {
      assertThat(note.messageDe()).doesNotContain("%s").doesNotContain("%d");
      assertThat(note.messageEn()).doesNotContain("%s").doesNotContain("%d");
    }
  }

  private static CanonicalResult roundTrip(Invoice original) {
    return switch (FORWARD.map(original)) {
      case UblDocument.CommercialInvoice(var document) -> REVERSE.map(document);
      case UblDocument.CreditNote(var document) -> REVERSE.map(document);
    };
  }

  @Provide
  Arbitrary<Invoice> canonicalInvoices() {
    return CanonicalInvoiceArbitraries.canonicalInvoices();
  }
}
