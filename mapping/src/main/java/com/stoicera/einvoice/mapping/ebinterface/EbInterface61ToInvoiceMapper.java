package com.stoicera.einvoice.mapping.ebinterface;

import com.helger.ebinterface.v61.Ebi61AccountType;
import com.helger.ebinterface.v61.Ebi61AddressType;
import com.helger.ebinterface.v61.Ebi61BillerType;
import com.helger.ebinterface.v61.Ebi61DetailsType;
import com.helger.ebinterface.v61.Ebi61DocumentTypeType;
import com.helger.ebinterface.v61.Ebi61InvoiceRecipientType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61ItemListType;
import com.helger.ebinterface.v61.Ebi61ListLineItemType;
import com.helger.ebinterface.v61.Ebi61PaymentMethodType;
import com.helger.ebinterface.v61.Ebi61TaxItemType;
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
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import com.stoicera.einvoice.mapping.conversion.ConversionNotes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Reads an ebInterface 6.1 document back into the canonical {@link Invoice} — the inverse of {@link
 * InvoiceToEbInterface61Mapper}, and the first half of any ebInterface → UBL conversion.
 *
 * <h2>Why this cannot be a pure inverse</h2>
 *
 * <p>The canonical model derives its VAT breakdown and totals from the lines and re-verifies them
 * (ADR-0003, derive-don't-trust). A source document, by contrast, <em>states</em> its totals, and
 * nothing forces a foreign system's arithmetic to agree with ours. This mapper therefore builds the
 * invoice from the lines — letting the model derive — and then compares the derivation against what
 * the document actually said. A disagreement becomes a {@code CONV-04} note at ERROR severity: the
 * canonical value wins, and the caller is told the document's own totals said something else. That
 * is the honest behaviour; silently adopting the source's totals would defeat the model, and
 * silently discarding them would hide a real discrepancy.
 *
 * <h2>What is read back, and what is noted</h2>
 *
 * <p>Every field {@link InvoiceToEbInterface61Mapper} writes is read back. Three of its mappings
 * are not information-preserving in reverse, and each produces a note:
 *
 * <ul>
 *   <li>The e-rechnung.gv.at no-UID convention {@code ATU00000000} becomes a canonical {@code null}
 *       VAT id again ({@code CONV-02}) — the placeholder means "this party has none", so carrying
 *       it through as a literal VAT id would invent a registration.
 *   <li>The {@code Country} element's German display name is dropped; only the ISO code survives,
 *       which is all the canonical {@link Address} models ({@code CONV-01}).
 *   <li>An exemption reason arrives as one free-text {@code Comment} with a lead-in the forward
 *       mapper composed, because ebInterface has no separate code element. It is read back as the
 *       reason's text with the lead-in stripped; the {@code VATEX} code cannot be recovered
 *       reliably from prose and is noted as lost ({@code CONV-01}).
 * </ul>
 *
 * <p>Stateless and safe to share.
 */
public final class EbInterface61ToInvoiceMapper {

  /**
   * The placeholder the forward mapper writes for a party with no VAT id.
   *
   * <p><strong>The convention is ambiguous, and nothing here can fix that.</strong> It is a
   * sentinel spelled inside the value space it guards: a document carrying literally {@code
   * ATU00000000} is read back as "this party has no VAT id", because that is what e-rechnung.gv.at
   * defines the string to mean, and there is no way to tell it apart from a hypothetical party
   * whose actual registration is that number. Reading it as a VAT id instead would be worse — it
   * would invent a registration for every Kleinunternehmer the convention was created for. The
   * reinterpretation is reported as a {@code CONV-02} note so a caller sees it happen rather than
   * discovering it later. (Found by a round-trip property test that generated the all-zero case.)
   */
  private static final String NO_UID_CONVENTION = "ATU00000000";

  /** Lead-ins the forward mapper composes onto an exemption comment. */
  private static final List<String> EXEMPTION_LEAD_INS =
      List.of("Übergang der Steuerschuld: ", "Steuerbefreiung: ");

  /**
   * Reads {@code ebi} into the canonical model.
   *
   * @param ebi a parsed ebInterface 6.1 document, never {@code null}
   * @return the canonical invoice plus every conversion note the read produced
   * @throws com.stoicera.einvoice.core.InvariantViolationException the document is well-formed
   *     ebInterface but describes an invoice the canonical model rejects (a malformed IBAN, a blank
   *     invoice number). Validation is the validation module's job; this mapper reads a document it
   *     is entitled to assume has been validated, and lets {@code core} speak when it has not.
   */
  public CanonicalResult map(Ebi61InvoiceType ebi) {
    List<Finding> notes = new ArrayList<>();
    Currency currency = currencyOf(ebi);

    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber(ebi.getInvoiceNumber())
            .type(typeOf(ebi))
            .issueDate(ebi.getInvoiceDateLocal())
            .currency(currency)
            .seller(billerParty(ebi.getBiller(), notes))
            .buyer(recipientParty(ebi.getInvoiceRecipient(), notes));

    if (ebi.getBiller() != null && ebi.getBiller().getInvoiceRecipientsBillerID() != null) {
      builder.supplierNumber(ebi.getBiller().getInvoiceRecipientsBillerID());
    }
    orderReference(ebi).ifPresent(builder::orderReference);
    delivery(ebi, builder);
    payment(ebi, builder);
    exemptionReasons(ebi, builder, notes);

    for (InvoiceLine line : lines(ebi, currency, notes)) {
      builder.addLine(line);
    }

    Invoice invoice = builder.build();
    notes.addAll(totalsDeviations(ebi, invoice));
    return new CanonicalResult(invoice, notes);
  }

  private static Currency currencyOf(Ebi61InvoiceType ebi) {
    String code = ebi.getInvoiceCurrency();
    return code == null ? Money.EUR : Currency.getInstance(code);
  }

  private static InvoiceTypeCode typeOf(Ebi61InvoiceType ebi) {
    return ebi.getDocumentType() == Ebi61DocumentTypeType.CREDIT_MEMO
        ? InvoiceTypeCode.CREDIT_NOTE
        : InvoiceTypeCode.COMMERCIAL_INVOICE;
  }

  private static Optional<String> orderReference(Ebi61InvoiceType ebi) {
    Ebi61InvoiceRecipientType recipient = ebi.getInvoiceRecipient();
    if (recipient == null || recipient.getOrderReference() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(recipient.getOrderReference().getOrderID());
  }

  private Party billerParty(Ebi61BillerType biller, List<Finding> notes) {
    if (biller == null) {
      return null; // core's constructor produces the one clear message
    }
    return party(biller.getVATIdentificationNumber(), biller.getAddress(), "Biller", notes);
  }

  private Party recipientParty(Ebi61InvoiceRecipientType recipient, List<Finding> notes) {
    if (recipient == null) {
      return null;
    }
    return party(
        recipient.getVATIdentificationNumber(), recipient.getAddress(), "InvoiceRecipient", notes);
  }

  private Party party(String vatId, Ebi61AddressType address, String where, List<Finding> notes) {
    if (address == null) {
      return null;
    }
    String canonicalVatId = vatId;
    if (NO_UID_CONVENTION.equals(vatId)) {
      canonicalVatId = null;
      notes.add(
          ConversionNotes.convention(
              where + "/VATIdentificationNumber",
              ("Die e-rechnung.gv.at-Konvention „%s“ (keine UID vorhanden) wurde als"
                      + " fehlende UID-Nummer übernommen, nicht als UID-Nummer selbst.")
                  .formatted(NO_UID_CONVENTION),
              ("The e-rechnung.gv.at convention \"%s\" (party has no VAT id) was read as an"
                      + " absent VAT id rather than as a VAT id in its own right.")
                  .formatted(NO_UID_CONVENTION)));
    }

    if (address.getCountry() != null
        && address.getCountry().getValue() != null
        && !address.getCountry().getValue().isBlank()) {
      notes.add(
          ConversionNotes.lost(
              where + "/Address/Country",
              "Der Klartext-Ländername wurde verworfen; das kanonische Modell führt ausschließlich"
                  + " den ISO-3166-1-alpha-2-Code.",
              "The plain-text country name was dropped; the canonical model carries only the"
                  + " ISO 3166-1 alpha-2 code."));
    }

    return new Party(
        address.getName(),
        new Address(
            address.getStreet(),
            address.getTown(),
            address.getZIP(),
            address.getCountry() == null ? null : address.getCountry().getCountryCode()),
        canonicalVatId,
        address.getEmail().stream().findFirst());
  }

  private static void delivery(Ebi61InvoiceType ebi, Invoice.Builder builder) {
    if (ebi.getDelivery() == null) {
      return;
    }
    if (ebi.getDelivery().getDateLocal() != null) {
      builder.deliveryDate(ebi.getDelivery().getDateLocal());
    } else if (ebi.getDelivery().getPeriod() != null) {
      builder.servicePeriod(
          new ServicePeriod(
              ebi.getDelivery().getPeriod().getFromDateLocal(),
              ebi.getDelivery().getPeriod().getToDateLocal()));
    }
  }

  private static void payment(Ebi61InvoiceType ebi, Invoice.Builder builder) {
    Ebi61PaymentMethodType method = ebi.getPaymentMethod();
    if (method != null && method.getUniversalBankTransaction() != null) {
      List<Ebi61AccountType> accounts =
          method.getUniversalBankTransaction().getBeneficiaryAccount();
      if (!accounts.isEmpty() && accounts.getFirst().getIBAN() != null) {
        builder.paymentMeans(
            new PaymentMeans(
                new Iban(accounts.getFirst().getIBAN()), accounts.getFirst().getBIC()));
      }
    }
    if (ebi.getPaymentConditions() != null) {
      if (ebi.getPaymentConditions().getDueDateLocal() != null) {
        builder.dueDate(ebi.getPaymentConditions().getDueDateLocal());
      }
      if (ebi.getPaymentConditions().getComment() != null) {
        builder.paymentTerms(ebi.getPaymentConditions().getComment());
      }
    }
  }

  /**
   * Reads exemption reasons back out of the {@code Tax/TaxItem/Comment} free text the forward
   * mapper composed. The lead-in it prefixed is stripped; the {@code VATEX} code it joined into the
   * same string is not recovered, because parsing it back out of prose would be guesswork — so the
   * text survives and the code is reported lost.
   */
  private void exemptionReasons(
      Ebi61InvoiceType ebi, Invoice.Builder builder, List<Finding> notes) {
    if (ebi.getTax() == null) {
      return;
    }
    for (Ebi61TaxItemType taxItem : ebi.getTax().getTaxItem()) {
      String comment = taxItem.getComment();
      if (comment == null || comment.isBlank() || taxItem.getTaxPercent() == null) {
        continue;
      }
      VatCategory category = categoryOf(taxItem.getTaxPercent().getTaxCategoryCode());
      if (category == null || !category.requiresExemptionReason()) {
        continue;
      }
      builder.exemptionReason(category, new VatExemptionReason(null, stripLeadIn(comment)));
      notes.add(
          ConversionNotes.lost(
              "Tax/TaxItem/Comment",
              "Der Befreiungsgrund wurde als Freitext übernommen; ebInterface führt Code (BT-121)"
                  + " und Text (BT-120) in einem einzigen Comment-Element, der Code ist daraus nicht"
                  + " verlässlich rekonstruierbar.",
              "The exemption reason was read as free text; ebInterface carries the code (BT-121) and"
                  + " the text (BT-120) in a single Comment element, and the code cannot be"
                  + " reliably recovered from it."));
    }
  }

  private static String stripLeadIn(String comment) {
    for (String leadIn : EXEMPTION_LEAD_INS) {
      if (comment.startsWith(leadIn)) {
        return comment.substring(leadIn.length());
      }
    }
    return comment;
  }

  private static VatCategory categoryOf(String code) {
    if (code == null) {
      return null;
    }
    for (VatCategory category : VatCategory.values()) {
      if (category.code().equals(code)) {
        return category;
      }
    }
    return null;
  }

  private static List<InvoiceLine> lines(
      Ebi61InvoiceType ebi, Currency currency, List<Finding> notes) {
    List<InvoiceLine> lines = new ArrayList<>();
    Ebi61DetailsType details = ebi.getDetails();
    if (details == null) {
      return lines;
    }
    for (Ebi61ItemListType itemList : details.getItemList()) {
      for (Ebi61ListLineItemType item : itemList.getListLineItem()) {
        lines.add(line(item, currency, lines.size() + 1, notes));
      }
    }
    return lines;
  }

  private static InvoiceLine line(
      Ebi61ListLineItemType item, Currency currency, int position, List<Finding> notes) {
    VatRate rate = rateOf(item);
    InvoiceLine line =
        new InvoiceLine(
            String.valueOf(position),
            item.getDescription().isEmpty() ? null : item.getDescription().getFirst(),
            item.getQuantity() == null ? null : item.getQuantity().getValue(),
            item.getQuantity() == null ? null : item.getQuantity().getUnit(),
            item.getUnitPrice() == null ? null : item.getUnitPrice().getValue(),
            rate);

    // The document states the line total; the canonical model derives it from quantity × price.
    // A disagreement is real information about the source, not a rounding curiosity to swallow.
    BigDecimal stated = item.getLineItemAmount();
    BigDecimal derived = line.netAmount(currency).amount();
    if (stated != null && stated.compareTo(derived) != 0) {
      notes.add(
          ConversionNotes.derivedTotalMismatch(
              "Details/ItemList/ListLineItem[%d]/LineItemAmount".formatted(position),
              ("Der ausgewiesene Zeilenbetrag %s weicht vom berechneten Betrag %s"
                      + " (Menge × Einzelpreis) ab; übernommen wurde der berechnete Betrag.")
                  .formatted(stated.toPlainString(), derived.toPlainString()),
              ("The stated line amount %s differs from the derived amount %s"
                      + " (quantity × unit price); the derived amount was used.")
                  .formatted(stated.toPlainString(), derived.toPlainString())));
    }
    return line;
  }

  private static VatRate rateOf(Ebi61ListLineItemType item) {
    Ebi61TaxItemType taxItem = item.getTaxItem();
    if (taxItem == null || taxItem.getTaxPercent() == null) {
      return null;
    }
    VatCategory category = categoryOf(taxItem.getTaxPercent().getTaxCategoryCode());
    return category == null ? null : new VatRate(category, taxItem.getTaxPercent().getValue());
  }

  /**
   * Compares the document's own stated document-level totals against the ones the canonical model
   * derived, and reports every disagreement.
   */
  private static List<Finding> totalsDeviations(Ebi61InvoiceType ebi, Invoice invoice) {
    List<Finding> notes = new ArrayList<>();
    addDeviation(
        notes,
        "TotalGrossAmount",
        ebi.getTotalGrossAmount(),
        invoice.totals().grossTotal().amount());
    addDeviation(
        notes, "PayableAmount", ebi.getPayableAmount(), invoice.totals().payableAmount().amount());
    return notes;
  }

  private static void addDeviation(
      List<Finding> notes, String element, BigDecimal stated, BigDecimal derived) {
    if (stated == null || stated.compareTo(derived) == 0) {
      return;
    }
    notes.add(
        ConversionNotes.derivedTotalMismatch(
            element,
            ("Der ausgewiesene Betrag %s in %s weicht vom aus den Zeilen berechneten Betrag %s"
                    + " ab; übernommen wurde der berechnete Betrag.")
                .formatted(stated.toPlainString(), element, derived.toPlainString()),
            ("The stated %s of %s differs from the amount derived from the lines, %s; the"
                    + " derived amount was used.")
                .formatted(element, stated.toPlainString(), derived.toPlainString())));
  }
}
