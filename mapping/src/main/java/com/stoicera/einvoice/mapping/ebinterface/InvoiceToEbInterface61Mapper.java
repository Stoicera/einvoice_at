package com.stoicera.einvoice.mapping.ebinterface;

import com.helger.ebinterface.v61.Ebi61AccountType;
import com.helger.ebinterface.v61.Ebi61AddressType;
import com.helger.ebinterface.v61.Ebi61BillerType;
import com.helger.ebinterface.v61.Ebi61CountryType;
import com.helger.ebinterface.v61.Ebi61DetailsType;
import com.helger.ebinterface.v61.Ebi61DocumentTypeType;
import com.helger.ebinterface.v61.Ebi61InvoiceRecipientType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61ItemListType;
import com.helger.ebinterface.v61.Ebi61ListLineItemType;
import com.helger.ebinterface.v61.Ebi61OrderReferenceType;
import com.helger.ebinterface.v61.Ebi61PaymentConditionsType;
import com.helger.ebinterface.v61.Ebi61PaymentMethodType;
import com.helger.ebinterface.v61.Ebi61TaxItemType;
import com.helger.ebinterface.v61.Ebi61TaxPercentType;
import com.helger.ebinterface.v61.Ebi61TaxType;
import com.helger.ebinterface.v61.Ebi61UnitPriceType;
import com.helger.ebinterface.v61.Ebi61UnitType;
import com.helger.ebinterface.v61.Ebi61UniversalBankTransactionType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
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
 *   <tr><td>{@code type} INVOICE / CREDIT_NOTE</td><td>{@code @DocumentType} =
 *       {@code Invoice} / {@code CreditMemo}</td>
 *       <td>via {@link Ebi61DocumentTypeType#INVOICE}/{@link Ebi61DocumentTypeType#CREDIT_MEMO}
 *       (javap-resolved constant names); attribute is XSD-required.</td></tr>
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
 *   <tr><td>{@code seller}</td><td>{@code Biller}: {@code VATIdentificationNumber},
 *       {@code Address}</td>
 *       <td>{@code VATIdentificationNumber} is XSD-required on {@code Biller}; copied verbatim from
 *       {@code seller.vatId()}.</td></tr>
 *   <tr><td>{@code supplierNumber}</td><td>{@code Biller/InvoiceRecipientsBillerID}</td>
 *       <td>Lieferantennummer; omitted when {@code null}.</td></tr>
 *   <tr><td>{@code buyer}</td><td>{@code InvoiceRecipient}: {@code VATIdentificationNumber},
 *       {@code Address}</td><td>{@code VATIdentificationNumber} XSD-required.</td></tr>
 *   <tr><td>{@code orderReference}</td><td>{@code InvoiceRecipient/OrderReference/OrderID}</td>
 *       <td>Auftragsreferenz; {@code OrderReference} inherited from {@code AbstractPartyType};
 *       omitted when {@code null}.</td></tr>
 *   <tr><td>{@code lines[i]}</td>
 *       <td>{@code Details/ItemList[0]/ListLineItem[i]}</td>
 *       <td>{@code PositionNumber} = i+1; {@code Description} += line description;
 *       {@code Quantity} value = quantity, {@code @Unit} = unit code (default {@code "C62"} when
 *       {@code null}); {@code UnitPrice} value = unit price; line {@code TaxItem} =
 *       ({@code TaxableAmount} = line net, {@code TaxPercent} value = rate %, {@code @TaxCategoryCode}
 *       = category letter); {@code LineItemAmount} = line net.</td></tr>
 *   <tr><td>{@code vatBreakdown[j]}</td><td>{@code Tax/TaxItem[j]}</td>
 *       <td>{@code TaxableAmount}, {@code TaxPercent}(+category), {@code TaxAmount}.</td></tr>
 *   <tr><td>exemption reason (categories AE/E)</td><td>{@code Tax/TaxItem[j]/Comment}</td>
 *       <td><strong>XSD decision:</strong> {@code TaxItemType} carries an optional {@code Comment}
 *       element, so the reason (category letter + VATEX code + text) is echoed there rather than in a
 *       root-level {@code Comment}.</td></tr>
 *   <tr><td>{@code totals.grossTotal}</td><td>{@code TotalGrossAmount}</td>
 *       <td>copied, never recomputed.</td></tr>
 *   <tr><td>{@code totals.payableAmount}</td><td>{@code PayableAmount}</td>
 *       <td>copied, never recomputed.</td></tr>
 *   <tr><td>{@code paymentMeans}</td>
 *       <td>{@code PaymentMethod/UniversalBankTransaction/BeneficiaryAccount[0]}</td>
 *       <td>{@code IBAN} = iban value, {@code BIC} = bic when present; whole
 *       {@code PaymentMethod} omitted when {@code paymentMeans} is {@code null}.</td></tr>
 *   <tr><td>{@code dueDate}</td><td>{@code PaymentConditions/DueDate}</td><td></td></tr>
 *   <tr><td>{@code paymentTerms}</td><td>{@code PaymentConditions/Comment}</td>
 *       <td><strong>XSD decision:</strong> {@code PaymentConditionsType} carries an optional
 *       {@code Comment} element, so free-text terms go there. {@code PaymentConditions} is emitted
 *       only when a due date or terms are present.</td></tr>
 * </table>
 */
public final class InvoiceToEbInterface61Mapper {

  /** UN/ECE Recommendation 20 code for "one/piece" — the ebInterface fallback unit. */
  private static final String DEFAULT_UNIT_CODE = "C62";

  private static final String GENERATING_SYSTEM = "einvoice-at";

  /** ISO 639-1 (the XSD's {@code LanguageType} is a 2-char token). */
  private static final String LANGUAGE_DE = "de";

  /**
   * Maps a canonical {@link Invoice} to a fully populated {@link Ebi61InvoiceType}. The result is a
   * schema-complete ebInterface 6.1 tree for any invoice whose parties carry a VAT id (required by
   * the XSD on both {@code Biller} and {@code InvoiceRecipient}).
   *
   * @param invoice the canonical invoice, never {@code null}
   * @return the mapped ebInterface 6.1 document
   */
  public Ebi61InvoiceType map(Invoice invoice) {
    Ebi61InvoiceType ebi = new Ebi61InvoiceType();
    Currency currency = invoice.currency();

    mapHeader(invoice, ebi);
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

  private Ebi61BillerType mapBiller(Invoice invoice) {
    Party seller = invoice.seller();
    Ebi61BillerType biller = new Ebi61BillerType();
    biller.setVATIdentificationNumber(seller.vatId());
    biller.setAddress(mapAddress(seller));
    if (invoice.supplierNumber() != null) {
      biller.setInvoiceRecipientsBillerID(invoice.supplierNumber());
    }
    return biller;
  }

  private Ebi61InvoiceRecipientType mapRecipient(Invoice invoice) {
    Party buyer = invoice.buyer();
    Ebi61InvoiceRecipientType recipient = new Ebi61InvoiceRecipientType();
    recipient.setVATIdentificationNumber(buyer.vatId());
    recipient.setAddress(mapAddress(buyer));
    if (invoice.orderReference() != null) {
      Ebi61OrderReferenceType orderReference = new Ebi61OrderReferenceType();
      orderReference.setOrderID(invoice.orderReference());
      recipient.setOrderReference(orderReference);
    }
    return recipient;
  }

  private Ebi61AddressType mapAddress(Party party) {
    Address address = party.address();
    Ebi61AddressType target = new Ebi61AddressType();
    target.setName(party.name());
    target.setStreet(address.street());
    target.setTown(address.city());
    target.setZIP(address.postalCode());

    Ebi61CountryType country = new Ebi61CountryType();
    country.setValue(address.countryCode());
    country.setCountryCode(address.countryCode());
    target.setCountry(country);
    return target;
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
    quantity.setUnit(Objects.requireNonNullElse(line.unitCode(), DEFAULT_UNIT_CODE));
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

  /** Category letter + VATEX code + free text, joined into one human-readable comment. */
  private String exemptionComment(VatCategory category, VatExemptionReason reason) {
    return Stream.of(category.code(), reason.code(), reason.text())
        .filter(Objects::nonNull)
        .collect(Collectors.joining(" | ", "Steuerbefreiung: ", ""));
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
