package com.stoicera.einvoice.mapping.conversion;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.validation.Finding;
import java.util.ArrayList;
import java.util.List;

/**
 * What a canonical invoice will lose on the way <em>out</em> to a given target format.
 *
 * <h2>Why this is separate from the mappers</h2>
 *
 * <p>The forward mappers stay pure: they build a tree and return it. Threading a note list through
 * them would complicate a signature every caller uses, to serve a question only the conversion path
 * asks. Losses on the way out are also fully determined by the invoice and the target — they need
 * no knowledge of the produced tree — so they are answered declaratively here, next to the
 * vocabulary that describes them and directly testable in isolation.
 *
 * <p>Losses on the way <em>in</em> are different: reading a foreign document discovers things (a
 * total that disagrees, a convention that had to be reinterpreted) that only the read itself knows.
 * Those are produced by the reverse mappers and arrive on a {@link CanonicalResult}.
 *
 * <p>Together the two sides make up a full {@link ConversionReport}: a conversion runs source →
 * canonical → target, and each leg can lose something different.
 */
public final class ConversionLosses {

  private ConversionLosses() {}

  /**
   * Every note describing what {@code target} cannot carry from {@code invoice}.
   *
   * @param invoice the canonical invoice about to be written
   * @param target the format it is being written to
   * @return the notes, in document order; empty when the target can carry everything present
   */
  public static List<Finding> writingTo(Invoice invoice, TargetFormat target) {
    return switch (target) {
      case EBINTERFACE_61 -> writingToEbInterface(invoice);
      case UBL -> writingToUbl(invoice);
    };
  }

  /**
   * ebInterface 6.1 is the narrower of the two targets, and each gap below is a real element the
   * format simply does not have — not a mapping this project has yet to write.
   */
  private static List<Finding> writingToEbInterface(Invoice invoice) {
    List<Finding> notes = new ArrayList<>();

    if (invoice.seller().electronicAddress().isPresent()
        || invoice.buyer().electronicAddress().isPresent()) {
      notes.add(
          ConversionNotes.lost(
              "seller/buyer electronicAddress",
              "Die elektronische Adresse (BT-34/BT-49) entfällt: ebInterface 6.1 kennt kein Element"
                  + " für eine Netzwerk-Zustelladresse. Für den Versand über Peppol ist das"
                  + " UBL-Format erforderlich.",
              "The electronic address (BT-34/BT-49) is dropped: ebInterface 6.1 has no element for a"
                  + " network delivery address at all. Sending via Peppol requires the UBL"
                  + " format."));
    }

    boolean hasExemptionCode =
        invoice.vatBreakdown().stream()
            .map(VatBreakdownEntry::exemptionReason)
            .anyMatch(reason -> reason != null && reason.code() != null);
    if (hasExemptionCode) {
      notes.add(
          ConversionNotes.relocated(
              "Tax/TaxItem/Comment",
              "Befreiungsgrund-Code (BT-121) und -Text (BT-120) werden in ein einziges"
                  + " Comment-Freitextfeld zusammengeführt; ebInterface 6.1 hat kein eigenes Element"
                  + " für den Code. Eine Rückkonvertierung durch diese Plattform stellt beide Teile"
                  + " wieder her; ein fremdes System, das nur den Freitext liest, sieht sie als"
                  + " einen einzigen Satz.",
              "The exemption reason code (BT-121) and text (BT-120) are merged into a single"
                  + " free-text Comment; ebInterface 6.1 has no dedicated element for the code."
                  + " Converting back through this platform restores both parts; a foreign system"
                  + " reading only the free text will see them as one sentence."));
    }

    // Line ids: ebInterface identifies a line by ordinal position, so a canonical id that is not
    // already "1", "2", … does not survive. Reported only when it would actually change something.
    boolean idsAreNotOrdinals = false;
    for (int i = 0; i < invoice.lines().size(); i++) {
      if (!invoice.lines().get(i).id().equals(String.valueOf(i + 1))) {
        idsAreNotOrdinals = true;
        break;
      }
    }
    if (idsAreNotOrdinals) {
      notes.add(
          ConversionNotes.lost(
              "lines[].id",
              "Zeilen-IDs entfallen: ebInterface 6.1 identifiziert eine Zeile über ihre"
                  + " Positionsnummer, nicht über eine ID. Die Zeilen werden fortlaufend"
                  + " durchnummeriert.",
              "Line ids are dropped: ebInterface 6.1 identifies a line by its position number rather"
                  + " than by an id. Lines are renumbered consecutively."));
    }

    return notes;
  }

  /**
   * UBL carries almost all of EN 16931, so there is only one thing to report — and it comes from
   * UBL's own asymmetry between its two document kinds rather than from a gap in the standard.
   */
  private static List<Finding> writingToUbl(Invoice invoice) {
    List<Finding> notes = new ArrayList<>();

    if (invoice.type() == InvoiceTypeCode.CREDIT_NOTE
        && invoice.dueDate() != null
        && invoice.paymentMeans() == null) {
      notes.add(
          ConversionNotes.lost(
              "dueDate",
              "Das Fälligkeitsdatum (BT-9) entfällt: eine UBL-Gutschrift hat kein DueDate-Element,"
                  + " sondern trägt das Datum an den Zahlungsdaten — und diese Gutschrift enthält"
                  + " keine Zahlungsdaten.",
              "The payment due date (BT-9) is dropped: a UBL credit note has no DueDate element and"
                  + " carries the date on its payment means instead — and this credit note has no"
                  + " payment means."));
    }

    return notes;
  }
}
