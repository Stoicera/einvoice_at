package com.stoicera.einvoice.mapping.ubl;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
import com.stoicera.einvoice.core.party.ElectronicAddress;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import java.util.Currency;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.AddressType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.BranchType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.ContactType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.CountryType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.CreditNoteLineType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.CustomerPartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.DeliveryType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.FinancialAccountType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.InvoiceLineType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.ItemType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.MonetaryTotalType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.OrderReferenceType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyIdentificationType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyLegalEntityType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyTaxSchemeType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PaymentMeansType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PaymentTermsType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PeriodType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PriceType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.SupplierPartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxCategoryType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxSchemeType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxSubtotalType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxTotalType;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;

/**
 * Hand-written mapper from the canonical {@link Invoice} (module {@code core}) to the ph-ubl 2.1
 * JAXB model, customised to <strong>Peppol BIS Billing 3.0</strong>.
 *
 * <p>Like its ebInterface counterpart this mapper performs <strong>no arithmetic</strong>: the
 * canonical model already derives and re-verifies every amount (ADR-0003, {@code
 * derive-don't-trust}), and the mapper only copies those amounts into the target tree. It is
 * stateless and therefore safe to share across threads.
 *
 * <h2>Which root element</h2>
 *
 * <p>UBL expresses the document kind as the root element, not as an attribute: BT-3 code 380 maps
 * to {@code ubl:Invoice} and 381 to {@code ubl:CreditNote}, which are different namespaces and
 * different JAXB types. {@link #map(Invoice)} therefore returns a {@link UblDocument}, and the
 * caller switches over it. Everything below the root is identical between the two apart from the
 * type-code element and the line quantity element, so the component builders here are shared.
 *
 * <h2>What is deliberately not invented</h2>
 *
 * <p>Peppol requires both parties' electronic addresses (BT-34/BT-49). Where the canonical party
 * carries none, this mapper emits <em>nothing</em> rather than synthesising one from the VAT id: an
 * electronic address is a mailbox on a network and a VAT id is not one, so a synthesised value
 * would route a real document to a wrong or non-existent recipient. The absence is reported by the
 * conversion report and, downstream, by the Peppol Schematron itself. Same policy, same reason, as
 * {@link ElectronicAddress}'s own Javadoc.
 *
 * <h2>Field mapping (canonical → UBL 2.1 / Peppol BIS Billing 3.0)</h2>
 *
 * <table>
 *   <caption>Canonical-to-UBL field mapping</caption>
 *   <tr><th>Canonical</th><th>UBL</th><th>Notes</th></tr>
 *   <tr><td>—</td><td>{@code cbc:CustomizationID}</td>
 *       <td>{@value #CUSTOMIZATION_ID} — the identifier Peppol BIS Billing 3.0 requires; taken from
 *       the OpenPeppol Schematron shipped with phive-rules, not from memory.</td></tr>
 *   <tr><td>—</td><td>{@code cbc:ProfileID}</td>
 *       <td>{@value #PROFILE_ID} — profile 01 (Billing). The rule set accepts
 *       {@code ...billing:NN:1.0}; 01 is the billing profile.</td></tr>
 *   <tr><td>{@code invoiceNumber}</td><td>{@code cbc:ID}</td><td>BT-1.</td></tr>
 *   <tr><td>{@code issueDate}</td><td>{@code cbc:IssueDate}</td><td>BT-2.</td></tr>
 *   <tr><td>{@code dueDate}</td>
 *       <td>{@code cbc:DueDate} (invoice) / {@code cac:PaymentMeans/cbc:PaymentDueDate} (credit
 *       note)</td>
 *       <td>BT-9, and one of the few places the two UBL document kinds genuinely differ: a {@code
 *       ubl:CreditNote} has no {@code cbc:DueDate} element at all. Rule UBL-CR-412 confirms the
 *       split — it forbids {@code PaymentMeans/PaymentDueDate} on an invoice and exempts credit
 *       notes by name. Omitted when absent; on a credit note that carries no payment means there is
 *       nowhere to put it, which the conversion report reports as a loss.</td></tr>
 *   <tr><td>{@code type}</td><td>{@code cbc:InvoiceTypeCode} / {@code cbc:CreditNoteTypeCode}</td>
 *       <td>BT-3, the literal {@code 380}/{@code 381} from {@code InvoiceTypeCode.code()}.</td></tr>
 *   <tr><td>{@code currency}</td><td>{@code cbc:DocumentCurrencyCode}</td>
 *       <td>BT-5; also stamped as {@code @currencyID} on every amount.</td></tr>
 *   <tr><td>{@code orderReference}</td><td>{@code cac:OrderReference/cbc:ID}</td>
 *       <td>BT-13 (Auftragsreferenz). This is what satisfies Peppol's "a buyer reference or a
 *       purchase order reference must be present" rule; omitted when absent, and the AT-B2G profile
 *       requires it anyway.</td></tr>
 *   <tr><td>{@code supplierNumber}</td>
 *       <td>{@code cac:AccountingSupplierParty/cac:Party/cac:PartyIdentification/cbc:ID}</td>
 *       <td><strong>Mapping decision:</strong> BT-29 (Seller identifier). The Lieferantennummer is
 *       assigned <em>by the buyer</em>, and EN 16931 has no BT for "seller identifier assigned by
 *       the buyer" — BT-29 carries an identifier of the seller without expressing who assigned it.
 *       UBL's {@code cac:AccountingSupplierParty/cbc:CustomerAssignedAccountID} says precisely that,
 *       but sits outside the EN 16931 syntax binding Peppol restricts to, so BT-29 is used and the
 *       nuance recorded here.</td></tr>
 *   <tr><td>{@code seller} / {@code buyer}</td>
 *       <td>{@code cac:AccountingSupplierParty/cac:Party},
 *       {@code cac:AccountingCustomerParty/cac:Party}</td><td>BG-4 / BG-7.</td></tr>
 *   <tr><td>{@code party.name}</td><td>{@code cac:PartyLegalEntity/cbc:RegistrationName}</td>
 *       <td>BT-27/BT-44, the element Peppol requires (rather than the optional
 *       {@code cac:PartyName}).</td></tr>
 *   <tr><td>{@code party.vatId}</td>
 *       <td>{@code cac:PartyTaxScheme/cbc:CompanyID} + {@code cac:TaxScheme/cbc:ID = VAT}</td>
 *       <td>BT-31/BT-48; the whole {@code cac:PartyTaxScheme} is omitted when the party has no VAT
 *       id. <strong>No ATU00000000 placeholder here:</strong> unlike ebInterface, whose XSD makes
 *       the element mandatory, UBL lets it be absent — so absence is represented as absence.</td></tr>
 *   <tr><td>{@code party.address}</td><td>{@code cac:PostalAddress}</td>
 *       <td>{@code cbc:StreetName} BT-35, {@code cbc:CityName} BT-37, {@code cbc:PostalZone} BT-38,
 *       {@code cac:Country/cbc:IdentificationCode} BT-40 (the ISO code; UBL has no element for a
 *       country display name, so unlike the ebInterface mapping nothing German is emitted).</td></tr>
 *   <tr><td>{@code party.email}</td><td>{@code cac:Contact/cbc:ElectronicMail}</td>
 *       <td>BT-43/BT-58; {@code cac:Contact} omitted entirely when absent.</td></tr>
 *   <tr><td>{@code party.electronicAddress}</td>
 *       <td>{@code cbc:EndpointID} + {@code @schemeID}</td>
 *       <td>BT-34/BT-49 with BT-34-1/BT-49-1; omitted when absent — never synthesised, see
 *       above.</td></tr>
 *   <tr><td>{@code deliveryDate}</td><td>{@code cac:Delivery/cbc:ActualDeliveryDate}</td>
 *       <td>BT-72.</td></tr>
 *   <tr><td>{@code servicePeriod}</td>
 *       <td>{@code cac:InvoicePeriod/cbc:StartDate}+{@code cbc:EndDate}</td>
 *       <td>BG-14. Note the two land in <em>different</em> UBL structures, where ebInterface put
 *       both under {@code Delivery}; core's mutual exclusion means at most one is ever
 *       emitted.</td></tr>
 *   <tr><td>{@code paymentMeans}</td><td>{@code cac:PaymentMeans}</td>
 *       <td>{@code cbc:PaymentMeansCode} = {@value #PAYMENT_MEANS_CREDIT_TRANSFER} (UNCL4461 credit
 *       transfer, BT-81) — the only means the canonical model can express, since it carries an IBAN
 *       and nothing else; {@code cac:PayeeFinancialAccount/cbc:ID} = IBAN (BT-84);
 *       {@code cac:FinancialInstitutionBranch/cbc:ID} = BIC (BT-86) when present. Omitted entirely
 *       when the invoice carries no payment means.</td></tr>
 *   <tr><td>{@code paymentTerms}</td><td>{@code cac:PaymentTerms/cbc:Note}</td><td>BT-20.</td></tr>
 *   <tr><td>{@code totals.taxTotal}</td><td>{@code cac:TaxTotal/cbc:TaxAmount}</td>
 *       <td>BT-110, copied, never recomputed.</td></tr>
 *   <tr><td>{@code vatBreakdown[j]}</td><td>{@code cac:TaxTotal/cac:TaxSubtotal[j]}</td>
 *       <td>BG-23: {@code cbc:TaxableAmount} BT-116, {@code cbc:TaxAmount} BT-117,
 *       {@code cac:TaxCategory/cbc:ID} BT-118 (the category letter S/Z/AE/E),
 *       {@code cbc:Percent} BT-119, {@code cac:TaxScheme/cbc:ID = VAT}.</td></tr>
 *   <tr><td>exemption reason (AE/E)</td>
 *       <td>{@code cac:TaxCategory/cbc:TaxExemptionReasonCode} + {@code cbc:TaxExemptionReason}</td>
 *       <td>BT-121 / BT-120. UBL has dedicated elements for both, so — unlike ebInterface, where the
 *       reason had to be folded into a free-text {@code Comment} — code and text stay separate and
 *       machine-readable. Each is emitted only when present.</td></tr>
 *   <tr><td>{@code totals}</td><td>{@code cac:LegalMonetaryTotal}</td>
 *       <td>{@code cbc:LineExtensionAmount} BT-106 and {@code cbc:TaxExclusiveAmount} BT-109 both
 *       from {@code netTotal} (the canonical model has no allowances/charges, so the two coincide —
 *       ADR-0003's deliberately-absent list); {@code cbc:TaxInclusiveAmount} BT-112 from
 *       {@code grossTotal}; {@code cbc:PayableAmount} BT-115.</td></tr>
 *   <tr><td>{@code lines[i]}</td><td>{@code cac:InvoiceLine} / {@code cac:CreditNoteLine}</td>
 *       <td>{@code cbc:ID} = the canonical line id verbatim (BT-126, not a regenerated position
 *       number — ebInterface needs a position, UBL does not);
 *       {@code cbc:InvoicedQuantity}/{@code cbc:CreditedQuantity} + {@code @unitCode} BT-129/BT-130;
 *       {@code cbc:LineExtensionAmount} BT-131 = line net; {@code cac:Item/cbc:Name} BT-153;
 *       {@code cac:Item/cac:ClassifiedTaxCategory} BT-151/BT-152;
 *       {@code cac:Price/cbc:PriceAmount} BT-146.</td></tr>
 * </table>
 */
public final class InvoiceToUblMapper {

  /**
   * The Peppol BIS Billing 3.0 customisation identifier (BT-24). Read out of the OpenPeppol
   * Schematron that phive-rules ships rather than transcribed from documentation, so it matches the
   * rule set that will judge the output.
   */
  static final String CUSTOMIZATION_ID =
      "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";

  /** The Peppol business process identifier (BT-23): profile 01, Billing. */
  static final String PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

  /** UBL's tax scheme identifier for value added tax. */
  private static final String TAX_SCHEME_VAT = "VAT";

  /**
   * UNCL 4461 code 30, "credit transfer" — the only payment means a canonical {@link PaymentMeans}
   * can describe, carrying an IBAN and an optional BIC and nothing else.
   */
  private static final String PAYMENT_MEANS_CREDIT_TRANSFER = "30";

  /**
   * Maps a canonical {@link Invoice} to the UBL document its type code calls for.
   *
   * @param invoice the canonical invoice, never {@code null}
   * @return a {@link UblDocument.CommercialInvoice} for BT-3 380, a {@link UblDocument.CreditNote}
   *     for 381
   */
  public UblDocument map(Invoice invoice) {
    return switch (invoice.type()) {
      case COMMERCIAL_INVOICE -> new UblDocument.CommercialInvoice(mapInvoice(invoice));
      case CREDIT_NOTE -> new UblDocument.CreditNote(mapCreditNote(invoice));
    };
  }

  private InvoiceType mapInvoice(Invoice invoice) {
    Currency currency = invoice.currency();
    InvoiceType ubl = new InvoiceType();

    ubl.setCustomizationID(CUSTOMIZATION_ID);
    ubl.setProfileID(PROFILE_ID);
    ubl.setID(invoice.invoiceNumber());
    ubl.setIssueDate(invoice.issueDate());
    if (invoice.dueDate() != null) {
      ubl.setDueDate(invoice.dueDate());
    }
    ubl.setInvoiceTypeCode(invoice.type().code());
    ubl.setDocumentCurrencyCode(currency.getCurrencyCode());
    if (invoice.orderReference() != null) {
      ubl.setOrderReference(orderReference(invoice.orderReference()));
    }
    invoice.servicePeriod().map(InvoiceToUblMapper::invoicePeriod).ifPresent(ubl::addInvoicePeriod);
    ubl.setAccountingSupplierParty(supplier(invoice));
    ubl.setAccountingCustomerParty(customer(invoice));
    invoice.deliveryDate().map(InvoiceToUblMapper::delivery).ifPresent(ubl::addDelivery);
    // No payment due date on an Invoice's PaymentMeans: rule UBL-CR-412 forbids it there, because
    // BT-9 already has its own cbc:DueDate element above.
    paymentMeans(invoice, null).ifPresent(ubl::addPaymentMeans);
    if (invoice.paymentTerms() != null) {
      ubl.addPaymentTerms(paymentTerms(invoice.paymentTerms()));
    }
    ubl.addTaxTotal(taxTotal(invoice, currency));
    ubl.setLegalMonetaryTotal(legalMonetaryTotal(invoice, currency));
    for (InvoiceLine line : invoice.lines()) {
      ubl.addInvoiceLine(invoiceLine(line, currency));
    }
    return ubl;
  }

  private CreditNoteType mapCreditNote(Invoice invoice) {
    Currency currency = invoice.currency();
    CreditNoteType ubl = new CreditNoteType();

    ubl.setCustomizationID(CUSTOMIZATION_ID);
    ubl.setProfileID(PROFILE_ID);
    ubl.setID(invoice.invoiceNumber());
    ubl.setIssueDate(invoice.issueDate());
    ubl.setCreditNoteTypeCode(invoice.type().code());
    ubl.setDocumentCurrencyCode(currency.getCurrencyCode());
    if (invoice.orderReference() != null) {
      ubl.setOrderReference(orderReference(invoice.orderReference()));
    }
    invoice.servicePeriod().map(InvoiceToUblMapper::invoicePeriod).ifPresent(ubl::addInvoicePeriod);
    ubl.setAccountingSupplierParty(supplier(invoice));
    ubl.setAccountingCustomerParty(customer(invoice));
    invoice.deliveryDate().map(InvoiceToUblMapper::delivery).ifPresent(ubl::addDelivery);
    // A UBL CreditNote has no cbc:DueDate element at all — BT-9 lives on the payment means instead,
    // which is precisely why rule UBL-CR-412 exempts credit notes from its "no PaymentDueDate here"
    // assertion. When the credit note carries no payment means there is nowhere to put the date;
    // that is a genuine conversion loss and the conversion report says so rather than this mapper
    // inventing a credit-transfer block to hang a date on.
    paymentMeans(invoice, invoice.dueDate()).ifPresent(ubl::addPaymentMeans);
    if (invoice.paymentTerms() != null) {
      ubl.addPaymentTerms(paymentTerms(invoice.paymentTerms()));
    }
    ubl.addTaxTotal(taxTotal(invoice, currency));
    ubl.setLegalMonetaryTotal(legalMonetaryTotal(invoice, currency));
    for (InvoiceLine line : invoice.lines()) {
      ubl.addCreditNoteLine(creditNoteLine(line, currency));
    }
    return ubl;
  }

  private static OrderReferenceType orderReference(String orderReference) {
    OrderReferenceType reference = new OrderReferenceType();
    reference.setID(orderReference);
    return reference;
  }

  private static PeriodType invoicePeriod(ServicePeriod period) {
    PeriodType invoicePeriod = new PeriodType();
    invoicePeriod.setStartDate(period.from());
    invoicePeriod.setEndDate(period.to());
    return invoicePeriod;
  }

  private static DeliveryType delivery(java.time.LocalDate deliveryDate) {
    DeliveryType delivery = new DeliveryType();
    delivery.setActualDeliveryDate(deliveryDate);
    return delivery;
  }

  private SupplierPartyType supplier(Invoice invoice) {
    PartyType party = party(invoice.seller());
    if (invoice.supplierNumber() != null) {
      PartyIdentificationType identification = new PartyIdentificationType();
      identification.setID(invoice.supplierNumber());
      party.addPartyIdentification(identification);
    }
    SupplierPartyType supplier = new SupplierPartyType();
    supplier.setParty(party);
    return supplier;
  }

  private CustomerPartyType customer(Invoice invoice) {
    CustomerPartyType customer = new CustomerPartyType();
    customer.setParty(party(invoice.buyer()));
    return customer;
  }

  private PartyType party(Party source) {
    PartyType party = new PartyType();

    source
        .electronicAddress()
        .ifPresent(address -> party.setEndpointID(address.value()).setSchemeID(address.scheme()));

    party.setPostalAddress(postalAddress(source));

    if (source.vatId() != null) {
      PartyTaxSchemeType taxScheme = new PartyTaxSchemeType();
      taxScheme.setCompanyID(source.vatId());
      taxScheme.setTaxScheme(vatScheme());
      party.addPartyTaxScheme(taxScheme);
    }

    PartyLegalEntityType legalEntity = new PartyLegalEntityType();
    legalEntity.setRegistrationName(source.name());
    party.addPartyLegalEntity(legalEntity);

    source.email().ifPresent(email -> party.setContact(contact(email)));

    return party;
  }

  private static AddressType postalAddress(Party source) {
    AddressType postalAddress = new AddressType();
    postalAddress.setStreetName(source.address().street());
    postalAddress.setCityName(source.address().city());
    postalAddress.setPostalZone(source.address().postalCode());

    CountryType country = new CountryType();
    country.setIdentificationCode(source.address().countryCode());
    postalAddress.setCountry(country);

    return postalAddress;
  }

  private static ContactType contact(String email) {
    ContactType contact = new ContactType();
    contact.setElectronicMail(email);
    return contact;
  }

  private static TaxSchemeType vatScheme() {
    TaxSchemeType scheme = new TaxSchemeType();
    scheme.setID(TAX_SCHEME_VAT);
    return scheme;
  }

  /**
   * The {@code cac:PaymentMeans} block, or empty when the invoice carries no payment means.
   *
   * @param paymentDueDate BT-9 to stamp onto the block, or {@code null} to leave it off. Only a
   *     credit note passes a date: a UBL {@code Invoice} carries BT-9 in its own {@code
   *     cbc:DueDate} and rule UBL-CR-412 forbids repeating it here, while a UBL {@code CreditNote}
   *     has no {@code cbc:DueDate} element at all and this is the only place the date fits.
   */
  private static java.util.Optional<PaymentMeansType> paymentMeans(
      Invoice invoice, java.time.LocalDate paymentDueDate) {
    PaymentMeans source = invoice.paymentMeans();
    if (source == null) {
      return java.util.Optional.empty();
    }

    FinancialAccountType account = new FinancialAccountType();
    account.setID(source.iban().value());
    if (source.bic() != null) {
      BranchType branch = new BranchType();
      branch.setID(source.bic());
      account.setFinancialInstitutionBranch(branch);
    }

    PaymentMeansType means = new PaymentMeansType();
    means.setPaymentMeansCode(PAYMENT_MEANS_CREDIT_TRANSFER);
    if (paymentDueDate != null) {
      means.setPaymentDueDate(paymentDueDate);
    }
    means.setPayeeFinancialAccount(account);
    return java.util.Optional.of(means);
  }

  private static PaymentTermsType paymentTerms(String terms) {
    PaymentTermsType paymentTerms = new PaymentTermsType();
    paymentTerms.addNote(
        new oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21.NoteType(terms));
    return paymentTerms;
  }

  private static TaxTotalType taxTotal(Invoice invoice, Currency currency) {
    TaxTotalType taxTotal = new TaxTotalType();
    taxTotal
        .setTaxAmount(invoice.totals().taxTotal().amount())
        .setCurrencyID(currency.getCurrencyCode());

    for (VatBreakdownEntry entry : invoice.vatBreakdown()) {
      TaxSubtotalType subtotal = new TaxSubtotalType();
      subtotal
          .setTaxableAmount(entry.taxableAmount().amount())
          .setCurrencyID(currency.getCurrencyCode());
      subtotal.setTaxAmount(entry.taxAmount().amount()).setCurrencyID(currency.getCurrencyCode());
      subtotal.setTaxCategory(taxCategory(entry.rate(), entry.exemptionReason()));
      taxTotal.addTaxSubtotal(subtotal);
    }
    return taxTotal;
  }

  /**
   * A {@code cac:TaxCategory} for a rate, with the exemption reason split across UBL's two
   * dedicated elements (BT-121 code, BT-120 text) instead of the single free-text comment
   * ebInterface forces. Each half is emitted only when the canonical reason carries it — {@link
   * VatExemptionReason} requires at least one of the two, never both.
   */
  private static TaxCategoryType taxCategory(VatRate rate, VatExemptionReason reason) {
    TaxCategoryType category = new TaxCategoryType();
    category.setID(rate.category().code());
    category.setPercent(rate.percentage());
    if (reason != null) {
      if (reason.code() != null) {
        category.setTaxExemptionReasonCode(reason.code());
      }
      if (reason.text() != null) {
        category.addTaxExemptionReason(
            new oasis.names.specification.ubl.schema.xsd.commonbasiccomponents_21
                .TaxExemptionReasonType(reason.text()));
      }
    }
    category.setTaxScheme(vatScheme());
    return category;
  }

  private static MonetaryTotalType legalMonetaryTotal(Invoice invoice, Currency currency) {
    String currencyCode = currency.getCurrencyCode();
    MonetaryTotalType total = new MonetaryTotalType();
    total.setLineExtensionAmount(invoice.totals().netTotal().amount()).setCurrencyID(currencyCode);
    total.setTaxExclusiveAmount(invoice.totals().netTotal().amount()).setCurrencyID(currencyCode);
    total.setTaxInclusiveAmount(invoice.totals().grossTotal().amount()).setCurrencyID(currencyCode);
    total.setPayableAmount(invoice.totals().payableAmount().amount()).setCurrencyID(currencyCode);
    return total;
  }

  private static InvoiceLineType invoiceLine(InvoiceLine line, Currency currency) {
    BigDecimal net = line.netAmount(currency).amount();
    InvoiceLineType ublLine = new InvoiceLineType();
    ublLine.setID(line.id());
    ublLine.setInvoicedQuantity(line.quantity()).setUnitCode(line.unitCode());
    ublLine.setLineExtensionAmount(net).setCurrencyID(currency.getCurrencyCode());
    ublLine.setItem(item(line));
    ublLine.setPrice(price(line, currency));
    return ublLine;
  }

  private static CreditNoteLineType creditNoteLine(InvoiceLine line, Currency currency) {
    BigDecimal net = line.netAmount(currency).amount();
    CreditNoteLineType ublLine = new CreditNoteLineType();
    ublLine.setID(line.id());
    ublLine.setCreditedQuantity(line.quantity()).setUnitCode(line.unitCode());
    ublLine.setLineExtensionAmount(net).setCurrencyID(currency.getCurrencyCode());
    ublLine.setItem(item(line));
    ublLine.setPrice(price(line, currency));
    return ublLine;
  }

  private static ItemType item(InvoiceLine line) {
    ItemType item = new ItemType();
    item.setName(line.description());
    // Line-level tax category (BT-151/BT-152). No exemption reason here: UBL carries it once per
    // document, in the TaxTotal breakdown, not repeated on every line.
    TaxCategoryType category = new TaxCategoryType();
    category.setID(line.vatRate().category().code());
    category.setPercent(line.vatRate().percentage());
    category.setTaxScheme(vatScheme());
    item.addClassifiedTaxCategory(category);
    return item;
  }

  private static PriceType price(InvoiceLine line, Currency currency) {
    PriceType price = new PriceType();
    price.setPriceAmount(line.unitPrice()).setCurrencyID(currency.getCurrencyCode());
    return price;
  }
}
