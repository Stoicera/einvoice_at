package com.stoicera.einvoice.mapping.ebinterface;

import com.helger.ebinterface.v61.Ebi61AccountType;
import com.helger.ebinterface.v61.Ebi61AddressType;
import com.helger.ebinterface.v61.Ebi61BillerType;
import com.helger.ebinterface.v61.Ebi61CountryType;
import com.helger.ebinterface.v61.Ebi61DeliveryType;
import com.helger.ebinterface.v61.Ebi61DetailsType;
import com.helger.ebinterface.v61.Ebi61DocumentTypeType;
import com.helger.ebinterface.v61.Ebi61InvoiceRecipientType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61ItemListType;
import com.helger.ebinterface.v61.Ebi61ListLineItemType;
import com.helger.ebinterface.v61.Ebi61NoPaymentType;
import com.helger.ebinterface.v61.Ebi61OrderReferenceType;
import com.helger.ebinterface.v61.Ebi61PaymentConditionsType;
import com.helger.ebinterface.v61.Ebi61PaymentMethodType;
import com.helger.ebinterface.v61.Ebi61PeriodType;
import com.helger.ebinterface.v61.Ebi61TaxItemType;
import com.helger.ebinterface.v61.Ebi61TaxPercentType;
import com.helger.ebinterface.v61.Ebi61TaxType;
import com.helger.ebinterface.v61.Ebi61UnitPriceType;
import com.helger.ebinterface.v61.Ebi61UnitType;
import com.helger.ebinterface.v61.Ebi61UniversalBankTransactionType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Hand-written mapper from the canonical {@link Invoice} (module {@code core}) to the
 * ph-ebinterface 6.1 JAXB model ({@link Ebi61InvoiceType}, module {@code formats-ebinterface}).
 *
 * <p>This mapper performs <strong>no arithmetic</strong>. The canonical model already derives and
 * re-verifies every amount (ADR-0003, {@code derive-don't-trust}); the mapper only copies those
 * amounts into the target tree. It is stateless and therefore safe to share across threads.
 *
 * <h2>Field mapping (canonical → ebInterface 6.1)</h2>
 *
 * <table>
 *   <caption>Canonical-to-ebInterface-6.1 field mapping</caption>
 *   <tr><th>Canonical</th><th>ebInterface 6.1</th><th>Notes</th></tr>
 *   <tr><td>{@code invoiceNumber}</td><td>{@code InvoiceNumber}</td><td></td></tr>
 *   <tr><td>{@code type} COMMERCIAL_INVOICE / CREDIT_NOTE</td><td>{@code @DocumentType} =
 *       {@code Invoice} / {@code CreditMemo}</td>
 *       <td>via {@link Ebi61DocumentTypeType#INVOICE}/{@link Ebi61DocumentTypeType#CREDIT_MEMO};
 *       attribute is XSD-required.</td></tr>
 *   <tr><td>{@code issueDate}</td><td>{@code InvoiceDate}</td>
 *       <td>{@code setInvoiceDate(LocalDate)} overload.</td></tr>
 *   <tr><td>{@code currency}</td><td>{@code @InvoiceCurrency}</td>
 *       <td>ISO 4217 via {@link Currency#getCurrencyCode()}.</td></tr>
 *   <tr><td>—</td><td>{@code @GeneratingSystem} = {@code "einvoice-at"}</td>
 *       <td>XSD-required attribute.</td></tr>
 *   <tr><td>—</td><td>{@code @Language} = {@code "de"}</td>
 *       <td><strong>XSD decision:</strong> {@code LanguageType} is an {@code xs:token} restricted
 *       to length 2 (ISO 639-1), so the plain 2-letter code {@code "de"} is used, not the
 *       ISO 639-2 {@code "ger"}.</td></tr>
 *   <tr><td>{@code deliveryDate} (BT-72)</td><td>{@code Delivery/Date}</td>
 *       <td>{@code setDate(LocalDate)} overload; mutually exclusive with {@code servicePeriod}
 *       (enforced by {@code core}, never both present). {@code Delivery} is omitted entirely when
 *       neither is present — the XSD makes it optional.</td></tr>
 *   <tr><td>{@code servicePeriod} (BG-14)</td><td>{@code Delivery/Period/FromDate}+{@code ToDate}</td>
 *       <td>{@code Ebi61PeriodType}, both dates via the {@code LocalDate} setter overloads.</td></tr>
 *   <tr><td>{@code seller}</td><td>{@code Biller}: {@code VATIdentificationNumber},
 *       {@code Address}</td>
 *       <td>{@code VATIdentificationNumber} is XSD-required on {@code Biller}; copied verbatim from
 *       {@code seller.vatId()}, or the e-rechnung.gv.at placeholder {@code "ATU00000000"} when the
 *       party has none (core permits {@code Party.vatId == null}, e.g. Kleinunternehmer). Source:
 *       e-rechnung.gv.at "Rechnungsinhalte", erechnung.gv.at/erb/de_AT/content, retrieved
 *       2026-07-24 — "Besitzen Rechnungssteller und/oder Rechnungsempfänger keine UID-Nummer, ist
 *       jeweils der Wert ‚ATU00000000‘ (8 mal die Null) einzugeben."</td></tr>
 *   <tr><td>{@code supplierNumber}</td><td>{@code Biller/InvoiceRecipientsBillerID}</td>
 *       <td>Lieferantennummer; omitted when {@code null}.</td></tr>
 *   <tr><td>{@code buyer}</td><td>{@code InvoiceRecipient}: {@code VATIdentificationNumber},
 *       {@code Address}</td><td>{@code VATIdentificationNumber} XSD-required; same no-UID convention
 *       ({@code "ATU00000000"}) as the {@code Biller} row.</td></tr>
 *   <tr><td>{@code address.countryCode} (both parties)</td><td>{@code Address/Country}</td>
 *       <td><strong>Domain decision:</strong> the {@code @CountryCode} attribute carries the ISO
 *       3166-1 alpha-2 code, and the element <em>text</em> carries its German display name
 *       ({@code "AT"} → {@code "Österreich"}) via {@link java.util.Locale} — AUSTRIAPRO's own
 *       samples render the human-readable name there. An unknown code falls back to the code as its
 *       own text.</td></tr>
 *   <tr><td>{@code seller.email} / {@code buyer.email}</td><td>{@code Address/Email}</td>
 *       <td>{@code addEmail(String)}; omitted (no {@code Email} element) when the party carries
 *       none — {@code EmailType} is a repeatable, optional XSD element.</td></tr>
 *   <tr><td>{@code orderReference}</td><td>{@code InvoiceRecipient/OrderReference/OrderID}</td>
 *       <td>Auftragsreferenz; {@code OrderReference} inherited from {@code AbstractPartyType};
 *       omitted when {@code null}.</td></tr>
 *   <tr><td>{@code lines[i]}</td>
 *       <td>{@code Details/ItemList[0]/ListLineItem[i]}</td>
 *       <td>{@code PositionNumber} = i+1; {@code Description} += line description;
 *       {@code Quantity} value = quantity, {@code @Unit} = unit code (BT-130, mandatory in core —
 *       copied verbatim); {@code UnitPrice} value = unit price; line {@code TaxItem} =
 *       ({@code TaxableAmount} = line net, {@code TaxPercent} value = rate %, {@code @TaxCategoryCode}
 *       = category letter); {@code LineItemAmount} = line net.</td></tr>
 *   <tr><td>{@code vatBreakdown[j]}</td><td>{@code Tax/TaxItem[j]}</td>
 *       <td>{@code TaxableAmount}, {@code TaxPercent}(+category), {@code TaxAmount}.</td></tr>
 *   <tr><td>exemption reason (categories AE/E)</td><td>{@code Tax/TaxItem[j]/Comment}</td>
 *       <td><strong>XSD decision:</strong> {@code TaxItemType} carries an optional {@code Comment}
 *       element, so the reason (lead-in + category letter + VATEX code + text) is echoed there rather
 *       than in a root-level {@code Comment}. <strong>Domain decision:</strong> AE (reverse charge)
 *       leads with {@code "Übergang der Steuerschuld: "} (§ 11 Abs 1a UStG Hinweis auf den Übergang
 *       der Steuerschuld — reverse charge is not a Steuerbefreiung), E (genuine exemption) leads with
 *       {@code "Steuerbefreiung: "}.</td></tr>
 *   <tr><td>{@code totals.grossTotal}</td><td>{@code TotalGrossAmount}</td>
 *       <td>copied, never recomputed.</td></tr>
 *   <tr><td>{@code totals.payableAmount}</td><td>{@code PayableAmount}</td>
 *       <td>copied, never recomputed.</td></tr>
 *   <tr><td>{@code paymentMeans}</td>
 *       <td>{@code PaymentMethod/UniversalBankTransaction/BeneficiaryAccount[0]}</td>
 *       <td>{@code IBAN} = iban value, {@code BIC} = bic when present. When {@code paymentMeans} is
 *       {@code null}: a {@code CREDIT_NOTE} emits {@code PaymentMethod/NoPayment} (the
 *       e-rechnung.gv.at recommendation for an effektive Gutschrift — no refund account), while a
 *       {@code COMMERCIAL_INVOICE} omits the whole {@code PaymentMethod} (the XSD makes it
 *       optional). A credit note <em>with</em> {@code paymentMeans} keeps the bank-transfer refund
 *       account.</td></tr>
 *   <tr><td>{@code dueDate}</td><td>{@code PaymentConditions/DueDate}</td><td></td></tr>
 *   <tr><td>{@code paymentTerms}</td><td>{@code PaymentConditions/Comment}</td>
 *       <td><strong>XSD decision:</strong> {@code PaymentConditionsType} carries an optional
 *       {@code Comment} element, so free-text terms go there. {@code PaymentConditions} is emitted
 *       only when a due date or terms are present.</td></tr>
 * </table>
 */
public final class InvoiceToEbInterface61Mapper {

  /**
   * The e-rechnung.gv.at placeholder UID for a party without a VAT id (Kleinunternehmer issuer or
   * private/small buyer). The ebInterface 6.1 XSD makes {@code VATIdentificationNumber} required on
   * both {@code Biller} and {@code InvoiceRecipient}, and core deliberately permits {@code
   * Party.vatId == null}; this national convention resolves the two.
   *
   * <p>Source: e-rechnung.gv.at, "Rechnungsinhalte" (<a
   * href="https://www.erechnung.gv.at/erb/de_AT/content">erechnung.gv.at/erb/de_AT/content</a>,
   * retrieved 2026-07-24): <em>"Besitzen Rechnungssteller und/oder Rechnungsempfänger keine
   * UID-Nummer, ist jeweils der Wert ‚ATU00000000‘ (8 mal die Null) einzugeben."</em>
   */
  private static final String NO_UID_CONVENTION = "ATU00000000";

  private static final String GENERATING_SYSTEM = "einvoice-at";

  /** ISO 639-1 (the XSD's {@code LanguageType} is a 2-char token). */
  private static final String LANGUAGE_DE = "de";

  /**
   * Maps a canonical {@link Invoice} to a fully populated {@link Ebi61InvoiceType}. The result is a
   * schema-complete ebInterface 6.1 tree for every canonical invoice: a party without a VAT id
   * (permitted by core) marshals the XSD-required {@code VATIdentificationNumber} as the
   * e-rechnung.gv.at {@link #NO_UID_CONVENTION} placeholder rather than omitting the element.
   *
   * @param invoice the canonical invoice, never {@code null}
   * @return the mapped ebInterface 6.1 document
   */
  public Ebi61InvoiceType map(Invoice invoice) {
    Ebi61InvoiceType ebi = new Ebi61InvoiceType();
    Currency currency = invoice.currency();

    mapHeader(invoice, ebi);
    Ebi61DeliveryType delivery = mapDelivery(invoice);
    if (delivery != null) {
      ebi.setDelivery(delivery);
    }
    ebi.setBiller(mapBiller(invoice));
    ebi.setInvoiceRecipient(mapRecipient(invoice));
    ebi.setDetails(mapDetails(invoice, currency));
    ebi.setTax(mapTax(invoice));
    mapTotals(invoice, ebi);
    mapPayment(invoice, ebi);

    return ebi;
  }

  private void mapHeader(Invoice invoice, Ebi61InvoiceType ebi) {
    ebi.setInvoiceNumber(invoice.invoiceNumber());
    ebi.setInvoiceDate(invoice.issueDate());
    ebi.setDocumentType(documentType(invoice));
    ebi.setInvoiceCurrency(invoice.currency().getCurrencyCode());
    ebi.setGeneratingSystem(GENERATING_SYSTEM);
    ebi.setLanguage(LANGUAGE_DE);
  }

  private Ebi61DocumentTypeType documentType(Invoice invoice) {
    return switch (invoice.type()) {
      case COMMERCIAL_INVOICE -> Ebi61DocumentTypeType.INVOICE;
      case CREDIT_NOTE -> Ebi61DocumentTypeType.CREDIT_MEMO;
    };
  }

  /**
   * {@code deliveryDate} (BT-72) and {@code servicePeriod} (BG-14) are mutually exclusive on {@link
   * Invoice} (core enforces it, § 11 Abs 1 Z 4 UStG), so at most one of the two branches below ever
   * fires; when neither is present this returns {@code null} and the caller omits the whole
   * optional {@code Delivery} element rather than emitting an empty one.
   */
  private Ebi61DeliveryType mapDelivery(Invoice invoice) {
    if (invoice.deliveryDate().isPresent()) {
      Ebi61DeliveryType delivery = new Ebi61DeliveryType();
      delivery.setDate(invoice.deliveryDate().get());
      return delivery;
    }
    if (invoice.servicePeriod().isPresent()) {
      ServicePeriod period = invoice.servicePeriod().get();
      Ebi61PeriodType ebiPeriod = new Ebi61PeriodType();
      ebiPeriod.setFromDate(period.from());
      ebiPeriod.setToDate(period.to());
      Ebi61DeliveryType delivery = new Ebi61DeliveryType();
      delivery.setPeriod(ebiPeriod);
      return delivery;
    }
    return null;
  }

  private Ebi61BillerType mapBiller(Invoice invoice) {
    Party seller = invoice.seller();
    Ebi61BillerType biller = new Ebi61BillerType();
    biller.setVATIdentificationNumber(vatIdOrConvention(seller.vatId()));
    biller.setAddress(mapAddress(seller));
    if (invoice.supplierNumber() != null) {
      biller.setInvoiceRecipientsBillerID(invoice.supplierNumber());
    }
    return biller;
  }

  private Ebi61InvoiceRecipientType mapRecipient(Invoice invoice) {
    Party buyer = invoice.buyer();
    Ebi61InvoiceRecipientType recipient = new Ebi61InvoiceRecipientType();
    recipient.setVATIdentificationNumber(vatIdOrConvention(buyer.vatId()));
    recipient.setAddress(mapAddress(buyer));
    if (invoice.orderReference() != null) {
      Ebi61OrderReferenceType orderReference = new Ebi61OrderReferenceType();
      orderReference.setOrderID(invoice.orderReference());
      recipient.setOrderReference(orderReference);
    }
    return recipient;
  }

  /**
   * The party's VAT id, or the e-rechnung.gv.at {@link #NO_UID_CONVENTION} placeholder when the
   * canonical party carries none — keeping the XSD-required {@code VATIdentificationNumber}
   * present.
   */
  private static String vatIdOrConvention(String vatId) {
    return vatId != null ? vatId : NO_UID_CONVENTION;
  }

  private Ebi61AddressType mapAddress(Party party) {
    Address address = party.address();
    Ebi61AddressType target = new Ebi61AddressType();
    target.setName(party.name());
    target.setStreet(address.street());
    target.setTown(address.city());
    target.setZIP(address.postalCode());

    Ebi61CountryType country = new Ebi61CountryType();
    country.setValue(germanCountryName(address.countryCode()));
    country.setCountryCode(address.countryCode());
    target.setCountry(country);

    party.email().ifPresent(target::addEmail);

    return target;
  }

  /**
   * The German display name for an ISO 3166-1 alpha-2 country code ({@code "AT"} → {@code
   * "Österreich"}); the {@code @CountryCode} attribute keeps the code itself. An unrecognised code
   * has no display name, so {@link Locale#getDisplayCountry(Locale)} returns the code unchanged —
   * the element text then simply echoes the code rather than being empty.
   */
  private static String germanCountryName(String countryCode) {
    return new Locale.Builder().setRegion(countryCode).build().getDisplayCountry(Locale.GERMAN);
  }

  private Ebi61DetailsType mapDetails(Invoice invoice, Currency currency) {
    Ebi61ItemListType itemList = new Ebi61ItemListType();
    int position = 1;
    for (InvoiceLine line : invoice.lines()) {
      itemList.addListLineItem(mapLine(line, position++, currency));
    }
    Ebi61DetailsType details = new Ebi61DetailsType();
    details.addItemList(itemList);
    return details;
  }

  private Ebi61ListLineItemType mapLine(InvoiceLine line, int position, Currency currency) {
    Ebi61ListLineItemType item = new Ebi61ListLineItemType();
    item.setPositionNumber(BigInteger.valueOf(position));
    item.addDescription(line.description());

    Ebi61UnitType quantity = new Ebi61UnitType(line.quantity());
    quantity.setUnit(line.unitCode());
    item.setQuantity(quantity);

    item.setUnitPrice(new Ebi61UnitPriceType(line.unitPrice()));

    BigDecimal net = line.netAmount(currency).amount();
    Ebi61TaxItemType taxItem = new Ebi61TaxItemType();
    taxItem.setTaxableAmount(net);
    taxItem.setTaxPercent(taxPercent(line.vatRate()));
    item.setTaxItem(taxItem);

    item.setLineItemAmount(net);
    return item;
  }

  private Ebi61TaxType mapTax(Invoice invoice) {
    Ebi61TaxType tax = new Ebi61TaxType();
    for (VatBreakdownEntry entry : invoice.vatBreakdown()) {
      Ebi61TaxItemType taxItem = new Ebi61TaxItemType();
      taxItem.setTaxableAmount(entry.taxableAmount().amount());
      taxItem.setTaxPercent(taxPercent(entry.rate()));
      taxItem.setTaxAmount(entry.taxAmount().amount());
      if (entry.exemptionReason() != null) {
        taxItem.setComment(exemptionComment(entry.rate().category(), entry.exemptionReason()));
      }
      tax.addTaxItem(taxItem);
    }
    return tax;
  }

  private Ebi61TaxPercentType taxPercent(VatRate rate) {
    Ebi61TaxPercentType taxPercent = new Ebi61TaxPercentType(rate.percentage());
    taxPercent.setTaxCategoryCode(rate.category().code());
    return taxPercent;
  }

  /**
   * Category-specific lead-in + category letter + VATEX code + free text, joined into one
   * human-readable comment. Reverse charge (AE) is <strong>not</strong> a Steuerbefreiung: § 11 Abs
   * 1a UStG requires the Hinweis auf den Übergang der Steuerschuld auf den Leistungsempfänger, so
   * AE leads with {@code "Übergang der Steuerschuld: "} while a genuine exemption (E) keeps {@code
   * "Steuerbefreiung: "}.
   */
  private String exemptionComment(VatCategory category, VatExemptionReason reason) {
    String leadIn =
        category == VatCategory.REVERSE_CHARGE
            ? "Übergang der Steuerschuld: "
            : "Steuerbefreiung: ";
    return Stream.of(category.code(), reason.code(), reason.text())
        .filter(Objects::nonNull)
        .collect(Collectors.joining(" | ", leadIn, ""));
  }

  private void mapTotals(Invoice invoice, Ebi61InvoiceType ebi) {
    ebi.setTotalGrossAmount(invoice.totals().grossTotal().amount());
    ebi.setPayableAmount(invoice.totals().payableAmount().amount());
  }

  private void mapPayment(Invoice invoice, Ebi61InvoiceType ebi) {
    PaymentMeans paymentMeans = invoice.paymentMeans();
    if (paymentMeans != null) {
      Ebi61AccountType account = new Ebi61AccountType();
      account.setIBAN(paymentMeans.iban().value());
      if (paymentMeans.bic() != null) {
        account.setBIC(paymentMeans.bic());
      }
      Ebi61UniversalBankTransactionType transaction = new Ebi61UniversalBankTransactionType();
      transaction.addBeneficiaryAccount(account);
      Ebi61PaymentMethodType paymentMethod = new Ebi61PaymentMethodType();
      paymentMethod.setUniversalBankTransaction(transaction);
      ebi.setPaymentMethod(paymentMethod);
    } else if (invoice.type() == InvoiceTypeCode.CREDIT_NOTE) {
      // e-rechnung.gv.at recommends NoPayment for an effektive Gutschrift (a credit note that moves
      // no money); a commercial invoice without payment means simply omits the optional block.
      Ebi61PaymentMethodType paymentMethod = new Ebi61PaymentMethodType();
      paymentMethod.setNoPayment(new Ebi61NoPaymentType());
      ebi.setPaymentMethod(paymentMethod);
    }

    if (invoice.dueDate() != null || invoice.paymentTerms() != null) {
      Ebi61PaymentConditionsType conditions = new Ebi61PaymentConditionsType();
      if (invoice.dueDate() != null) {
        conditions.setDueDate(invoice.dueDate());
      }
      if (invoice.paymentTerms() != null) {
        conditions.setComment(invoice.paymentTerms());
      }
      ebi.setPaymentConditions(conditions);
    }
  }
}
