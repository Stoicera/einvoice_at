package com.stoicera.einvoice.mapping.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.mapping.CanonicalInvoiceArbitraries;
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Round-trip properties for canonical → ebInterface 6.1 → canonical.
 *
 * <p>A round trip is the sharpest test a pair of mappers can be given: it needs no hand-written
 * expectation, it covers the whole input space the generators can reach, and any field one mapper
 * writes but the other cannot read shows up immediately as an inequality. What it must <em>not</em>
 * do is assert plain record equality and call it a day — the two mappers are deliberately not exact
 * inverses, so this suite states field by field what survives and, just as importantly, what does
 * not.
 *
 * <p>What cannot survive, by construction rather than by omission:
 *
 * <ul>
 *   <li><strong>Line ids.</strong> ebInterface identifies a line by ordinal position, not by an id,
 *       so the forward mapper writes {@code PositionNumber} and the canonical id is not in the
 *       document. The reverse mapper therefore regenerates ids as {@code 1..n}. Order and content
 *       survive; the original id strings do not.
 *   <li><strong>Electronic addresses (BT-34/BT-49).</strong> ebInterface 6.1 has no element for a
 *       network routing address at all.
 * </ul>
 *
 * <p><strong>Exemption reason codes (BT-121) used to be on that list and are not any more.</strong>
 * ebInterface does fold code and text into one {@code Comment}, but the forward mapper folds them
 * with a structure the reverse mapper can unfold, so both halves survive a trip through a document
 * this platform wrote — see {@code EbInterface61ToInvoiceMapper.parseExemptionComment}. The
 * property below pins that, and it is not a formality: while the code was being discarded, the
 * category letter it was discarded alongside stayed in the text and the forward mapper prefixed a
 * fresh one on the way out, so the comment grew on every round trip (M4 hostile review, F3a).
 */
class EbInterface61RoundTripPropertyTest {

  private static final InvoiceToEbInterface61Mapper FORWARD = new InvoiceToEbInterface61Mapper();
  private static final EbInterface61ToInvoiceMapper REVERSE = new EbInterface61ToInvoiceMapper();
  private static final EbInterface61Strategy STRATEGY = new EbInterface61Strategy();

  @Property(tries = 200)
  void headerAndPartyFieldsSurviveTheRoundTrip(@ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    assertThat(roundTripped.invoiceNumber()).isEqualTo(original.invoiceNumber());
    assertThat(roundTripped.type()).isEqualTo(original.type());
    assertThat(roundTripped.issueDate()).isEqualTo(original.issueDate());
    assertThat(roundTripped.dueDate()).isEqualTo(original.dueDate());
    assertThat(roundTripped.currency()).isEqualTo(original.currency());
    assertThat(roundTripped.orderReference()).isEqualTo(original.orderReference());
    assertThat(roundTripped.supplierNumber()).isEqualTo(original.supplierNumber());
    assertThat(roundTripped.paymentTerms()).isEqualTo(original.paymentTerms());
    assertThat(roundTripped.deliveryDate()).isEqualTo(original.deliveryDate());
    assertThat(roundTripped.servicePeriod()).isEqualTo(original.servicePeriod());
    assertThat(roundTripped.paymentMeans()).isEqualTo(original.paymentMeans());

    assertThat(roundTripped.seller().name()).isEqualTo(original.seller().name());
    assertThat(roundTripped.seller().vatId()).isEqualTo(original.seller().vatId());
    assertThat(roundTripped.seller().address()).isEqualTo(original.seller().address());
    assertThat(roundTripped.seller().email()).isEqualTo(original.seller().email());
    assertThat(roundTripped.buyer().name()).isEqualTo(original.buyer().name());
    assertThat(roundTripped.buyer().vatId()).isEqualTo(original.buyer().vatId());
    assertThat(roundTripped.buyer().address()).isEqualTo(original.buyer().address());
    assertThat(roundTripped.buyer().email()).isEqualTo(original.buyer().email());
  }

  /** Every line's economic content survives; only the id is regenerated (see the class Javadoc). */
  @Property(tries = 200)
  void lineContentSurvivesEvenThoughIdsAreRegenerated(
      @ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    assertThat(roundTripped.lines()).hasSameSizeAs(original.lines());
    for (int i = 0; i < original.lines().size(); i++) {
      InvoiceLine before = original.lines().get(i);
      InvoiceLine after = roundTripped.lines().get(i);

      assertThat(after.description()).isEqualTo(before.description());
      assertThat(after.quantity()).isEqualByComparingTo(before.quantity());
      assertThat(after.unitCode()).isEqualTo(before.unitCode());
      assertThat(after.unitPrice()).isEqualByComparingTo(before.unitPrice());
      assertThat(after.vatRate()).isEqualTo(before.vatRate());
      assertThat(after.id()).isEqualTo(String.valueOf(i + 1));
    }
  }

  /**
   * The arithmetic is identical on both sides, which is the whole point of the canonical model
   * deriving rather than trusting: nothing about a trip through a foreign syntax may move an
   * amount.
   */
  @Property(tries = 200)
  void totalsAndBreakdownAreUnchanged(@ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    assertThat(roundTripped.totals()).isEqualTo(original.totals());
    assertThat(roundTripped.vatBreakdown()).hasSameSizeAs(original.vatBreakdown());
    for (int i = 0; i < original.vatBreakdown().size(); i++) {
      assertThat(roundTripped.vatBreakdown().get(i).rate())
          .isEqualTo(original.vatBreakdown().get(i).rate());
      assertThat(roundTripped.vatBreakdown().get(i).taxableAmount())
          .isEqualTo(original.vatBreakdown().get(i).taxableAmount());
      assertThat(roundTripped.vatBreakdown().get(i).taxAmount())
          .isEqualTo(original.vatBreakdown().get(i).taxAmount());
    }
  }

  /**
   * The exemption reason survives whole — code <em>and</em> text — through a document this platform
   * wrote. See the class Javadoc for why this used to be listed as impossible.
   */
  @Property(tries = 200)
  void exemptionReasonsSurviveWithBothCodeAndText(@ForAll("canonicalInvoices") Invoice original) {
    Invoice roundTripped = roundTrip(original).invoice();

    for (int i = 0; i < original.vatBreakdown().size(); i++) {
      assertThat(roundTripped.vatBreakdown().get(i).exemptionReason())
          .isEqualTo(original.vatBreakdown().get(i).exemptionReason());
    }
  }

  /**
   * Emitting, reading and re-emitting must be a fixed point: the second document is byte-identical
   * to the first.
   *
   * <p>This is the property that would have caught the exemption comment growing on every trip (M4
   * hostile review, F3a). The canonical-model assertions above could not see it — they compare
   * {@code Invoice} objects, and the corruption lived in the emitted XML. Comparing the documents
   * closes that blind spot for every field at once, which is worth more than the one bug it found.
   */
  @Property(tries = 200)
  void reEmittingAReadDocumentReproducesItExactly(@ForAll("canonicalInvoices") Invoice original) {
    String once = STRATEGY.write(FORWARD.map(original));
    String twice = STRATEGY.write(FORWARD.map(REVERSE.map(FORWARD.map(original)).invoice()));

    assertThat(twice).isEqualTo(once);
  }

  /**
   * A round trip of our own output never reports a totals deviation. {@code CONV-04} exists for
   * foreign documents whose stated totals disagree with ours; seeing one here would mean our own
   * forward mapper wrote amounts our own model does not derive.
   */
  @Property(tries = 200)
  void ourOwnOutputNeverDeviatesOnTotals(@ForAll("canonicalInvoices") Invoice original) {
    assertThat(roundTrip(original).notes()).extracting(Finding::ruleId).doesNotContain("CONV-04");
  }

  /**
   * Guards a whole class of message bug: a note built by concatenating literals and then calling
   * {@code formatted} on only the last of them leaves {@code %s} in the text a user reads. That
   * happened while writing this mapper — caught by review, and pinned here so it cannot return
   * quietly.
   */
  @Property(tries = 200)
  void noNoteLeaksAnUnsubstitutedFormatSpecifier(@ForAll("canonicalInvoices") Invoice original) {
    for (Finding note : roundTrip(original).notes()) {
      assertThat(note.messageDe()).doesNotContain("%s").doesNotContain("%d");
      assertThat(note.messageEn()).doesNotContain("%s").doesNotContain("%d");
    }
  }

  /** Every note carries a German message — the repo-wide rule, applied to conversion notes too. */
  @Property(tries = 200)
  void everyNoteIsGermanFirst(@ForAll("canonicalInvoices") Invoice original) {
    for (Finding note : roundTrip(original).notes()) {
      assertThat(note.messageDe()).isNotBlank();
      assertThat(note.messageEn()).isNotBlank();
      assertThat(note.ruleId()).startsWith("CONV-");
    }
  }

  private static CanonicalResult roundTrip(Invoice original) {
    return REVERSE.map(FORWARD.map(original));
  }

  @Provide
  Arbitrary<Invoice> canonicalInvoices() {
    return CanonicalInvoiceArbitraries.canonicalInvoices();
  }
}
