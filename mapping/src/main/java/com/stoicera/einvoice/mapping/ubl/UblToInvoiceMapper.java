package com.stoicera.einvoice.mapping.ubl;

import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
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
import com.stoicera.einvoice.mapping.conversion.CanonicalResult;
import com.stoicera.einvoice.mapping.conversion.ConversionNotes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.CustomerPartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.MonetaryTotalType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PaymentMeansType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.PeriodType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.SupplierPartyType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxCategoryType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxSubtotalType;
import oasis.names.specification.ubl.schema.xsd.commonaggregatecomponents_21.TaxTotalType;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;

/**
 * Reads a UBL 2.1 billing document back into the canonical {@link Invoice} — the inverse of {@link
 * InvoiceToUblMapper}, and the first half of any UBL → ebInterface conversion.
 *
 * <p>The same derive-don't-trust discipline as the ebInterface reverse mapper: the invoice is built
 * from the lines and the model derives the breakdown and totals, then the derivation is compared
 * against what the document stated. A disagreement is a {@code CONV-04} note at ERROR severity, not
 * a silently adopted foreign total.
 *
 * <p>UBL's two document kinds differ in exactly three places — the root type, the type-code
 * element, and the line quantity element ({@code InvoicedQuantity} vs {@code CreditedQuantity}) —
 * plus where the due date lives. The two public {@code map} overloads therefore normalise those
 * differences into a shared body rather than duplicating the read.
 *
 * <p>Stateless and safe to share.
 */
public final class UblToInvoiceMapper {

  /** Reads a UBL {@code Invoice} (BT-3 380). */
  public CanonicalResult map(InvoiceType ubl) {
    List<LineData> lines = new ArrayList<>();
    for (var line : ubl.getInvoiceLine()) {
      lines.add(
          new LineData(
              line.getIDValue(),
              line.getItem() == null ? null : line.getItem().getNameValue(),
              line.getInvoicedQuantityValue(),
              line.getInvoicedQuantity() == null ? null : line.getInvoicedQuantity().getUnitCode(),
              line.getPrice() == null ? null : line.getPrice().getPriceAmountValue(),
              line.getLineExtensionAmountValue(),
              line.getItem() == null || line.getItem().getClassifiedTaxCategory().isEmpty()
                  ? null
                  : line.getItem().getClassifiedTaxCategory().getFirst()));
    }
    return map(
        new Header(
            ubl.getIDValue(),
            InvoiceTypeCode.COMMERCIAL_INVOICE,
            ubl.getIssueDateValueLocal(),
            ubl.getDueDateValueLocal(),
            ubl.getDocumentCurrencyCodeValue(),
            ubl.getOrderReference() == null ? null : ubl.getOrderReference().getIDValue(),
            ubl.getAccountingSupplierParty(),
            ubl.getAccountingCustomerParty(),
            ubl.getInvoicePeriod().isEmpty() ? null : ubl.getInvoicePeriod().getFirst(),
            ubl.getDelivery().isEmpty()
                ? null
                : ubl.getDelivery().getFirst().getActualDeliveryDateValueLocal(),
            ubl.getPaymentMeans().isEmpty() ? null : ubl.getPaymentMeans().getFirst(),
            ubl.getPaymentTerms().isEmpty() || ubl.getPaymentTerms().getFirst().getNote().isEmpty()
                ? null
                : ubl.getPaymentTerms().getFirst().getNote().getFirst().getValue(),
            ubl.getTaxTotal().isEmpty() ? null : ubl.getTaxTotal().getFirst(),
            ubl.getLegalMonetaryTotal()),
        lines);
  }

  /** Reads a UBL {@code CreditNote} (BT-3 381). */
  public CanonicalResult map(CreditNoteType ubl) {
    List<LineData> lines = new ArrayList<>();
    for (var line : ubl.getCreditNoteLine()) {
      lines.add(
          new LineData(
              line.getIDValue(),
              line.getItem() == null ? null : line.getItem().getNameValue(),
              line.getCreditedQuantityValue(),
              line.getCreditedQuantity() == null ? null : line.getCreditedQuantity().getUnitCode(),
              line.getPrice() == null ? null : line.getPrice().getPriceAmountValue(),
              line.getLineExtensionAmountValue(),
              line.getItem() == null || line.getItem().getClassifiedTaxCategory().isEmpty()
                  ? null
                  : line.getItem().getClassifiedTaxCategory().getFirst()));
    }
    PaymentMeansType paymentMeans =
        ubl.getPaymentMeans().isEmpty() ? null : ubl.getPaymentMeans().getFirst();
    return map(
        new Header(
            ubl.getIDValue(),
            InvoiceTypeCode.CREDIT_NOTE,
            ubl.getIssueDateValueLocal(),
            // A UBL CreditNote has no cbc:DueDate; BT-9 lives on the payment means (rule UBL-CR-412
            // exempts credit notes from the "not here" assertion precisely because this is where it
            // belongs).
            paymentMeans == null ? null : paymentMeans.getPaymentDueDateValueLocal(),
            ubl.getDocumentCurrencyCodeValue(),
            ubl.getOrderReference() == null ? null : ubl.getOrderReference().getIDValue(),
            ubl.getAccountingSupplierParty(),
            ubl.getAccountingCustomerParty(),
            ubl.getInvoicePeriod().isEmpty() ? null : ubl.getInvoicePeriod().getFirst(),
            ubl.getDelivery().isEmpty()
                ? null
                : ubl.getDelivery().getFirst().getActualDeliveryDateValueLocal(),
            paymentMeans,
            ubl.getPaymentTerms().isEmpty() || ubl.getPaymentTerms().getFirst().getNote().isEmpty()
                ? null
                : ubl.getPaymentTerms().getFirst().getNote().getFirst().getValue(),
            ubl.getTaxTotal().isEmpty() ? null : ubl.getTaxTotal().getFirst(),
            ubl.getLegalMonetaryTotal()),
        lines);
  }

  private CanonicalResult map(Header header, List<LineData> lineData) {
    List<Finding> notes = new ArrayList<>();
    Currency currency =
        header.currencyCode() == null ? Money.EUR : Currency.getInstance(header.currencyCode());

    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber(header.id())
            .type(header.type())
            .issueDate(header.issueDate())
            .currency(currency)
            .seller(party(header.supplier() == null ? null : header.supplier().getParty()))
            .buyer(party(header.customer() == null ? null : header.customer().getParty()));

    if (header.dueDate() != null) {
      builder.dueDate(header.dueDate());
    }
    if (header.orderReference() != null) {
      builder.orderReference(header.orderReference());
    }
    supplierNumber(header).ifPresent(builder::supplierNumber);
    if (header.deliveryDate() != null) {
      builder.deliveryDate(header.deliveryDate());
    } else if (header.invoicePeriod() != null) {
      builder.servicePeriod(
          new ServicePeriod(
              header.invoicePeriod().getStartDateValueLocal(),
              header.invoicePeriod().getEndDateValueLocal()));
    }
    paymentMeans(header.paymentMeans()).ifPresent(builder::paymentMeans);
    if (header.paymentTerms() != null) {
      builder.paymentTerms(header.paymentTerms());
    }
    exemptionReasons(header.taxTotal(), builder);

    for (LineData data : lineData) {
      builder.addLine(line(data, currency, notes));
    }

    Invoice invoice = builder.build();
    notes.addAll(totalsDeviations(header.monetaryTotal(), header.taxTotal(), invoice));
    return new CanonicalResult(invoice, notes);
  }

  /**
   * BT-29, which the forward mapper writes as the seller's first {@code cac:PartyIdentification}.
   */
  private static Optional<String> supplierNumber(Header header) {
    if (header.supplier() == null || header.supplier().getParty() == null) {
      return Optional.empty();
    }
    return header.supplier().getParty().getPartyIdentification().stream()
        .findFirst()
        .map(identification -> identification.getIDValue());
  }

  private static Party party(PartyType source) {
    if (source == null) {
      return null; // core's constructor produces the one clear message
    }
    var postalAddress = source.getPostalAddress();
    Address address =
        postalAddress == null
            ? null
            : new Address(
                postalAddress.getStreetNameValue(),
                postalAddress.getCityNameValue(),
                postalAddress.getPostalZoneValue(),
                postalAddress.getCountry() == null
                    ? null
                    : postalAddress.getCountry().getIdentificationCodeValue());

    String name =
        source.getPartyLegalEntity().isEmpty()
            ? null
            : source.getPartyLegalEntity().getFirst().getRegistrationNameValue();

    String vatId =
        source.getPartyTaxScheme().isEmpty()
            ? null
            : source.getPartyTaxScheme().getFirst().getCompanyIDValue();

    Optional<String> email =
        source.getContact() == null
            ? Optional.empty()
            : Optional.ofNullable(source.getContact().getElectronicMailValue());

    Optional<ElectronicAddress> endpoint =
        source.getEndpointID() == null
            ? Optional.empty()
            : Optional.of(
                new ElectronicAddress(
                    source.getEndpointID().getSchemeID(), source.getEndpointIDValue()));

    return new Party(name, address, vatId, email, endpoint);
  }

  private static Optional<PaymentMeans> paymentMeans(PaymentMeansType source) {
    if (source == null || source.getPayeeFinancialAccount() == null) {
      return Optional.empty();
    }
    var account = source.getPayeeFinancialAccount();
    if (account.getIDValue() == null) {
      return Optional.empty();
    }
    String bic =
        account.getFinancialInstitutionBranch() == null
            ? null
            : account.getFinancialInstitutionBranch().getIDValue();
    return Optional.of(new PaymentMeans(new Iban(account.getIDValue()), bic));
  }

  /**
   * UBL keeps the exemption code and text in two dedicated elements, so unlike the ebInterface
   * reverse mapper this one recovers both — nothing is lost and there is nothing to note.
   */
  private static void exemptionReasons(TaxTotalType taxTotal, Invoice.Builder builder) {
    if (taxTotal == null) {
      return;
    }
    for (TaxSubtotalType subtotal : taxTotal.getTaxSubtotal()) {
      TaxCategoryType category = subtotal.getTaxCategory();
      if (category == null) {
        continue;
      }
      VatCategory vatCategory = categoryOf(category.getIDValue());
      if (vatCategory == null || !vatCategory.requiresExemptionReason()) {
        continue;
      }
      String code = category.getTaxExemptionReasonCodeValue();
      String text =
          category.getTaxExemptionReason().isEmpty()
              ? null
              : category.getTaxExemptionReason().getFirst().getValue();
      if (code != null || text != null) {
        builder.exemptionReason(vatCategory, new VatExemptionReason(code, text));
      }
    }
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

  private static InvoiceLine line(LineData data, Currency currency, List<Finding> notes) {
    VatRate rate =
        data.taxCategory() == null
            ? null
            : rateOf(data.taxCategory().getIDValue(), data.taxCategory().getPercentValue());

    InvoiceLine line =
        new InvoiceLine(
            data.id(), data.name(), data.quantity(), data.unitCode(), data.price(), rate);

    BigDecimal stated = data.lineExtensionAmount();
    BigDecimal derived = line.netAmount(currency).amount();
    if (stated != null && stated.compareTo(derived) != 0) {
      notes.add(
          ConversionNotes.derivedTotalMismatch(
              "cac:InvoiceLine[%s]/cbc:LineExtensionAmount".formatted(data.id()),
              ("Der ausgewiesene Zeilenbetrag %s weicht vom berechneten Betrag %s"
                      + " (Menge × Einzelpreis) ab; übernommen wurde der berechnete Betrag.")
                  .formatted(stated.toPlainString(), derived.toPlainString()),
              ("The stated line amount %s differs from the derived amount %s"
                      + " (quantity × unit price); the derived amount was used.")
                  .formatted(stated.toPlainString(), derived.toPlainString())));
    }
    return line;
  }

  private static VatRate rateOf(String categoryCode, BigDecimal percent) {
    VatCategory category = categoryOf(categoryCode);
    return category == null || percent == null ? null : new VatRate(category, percent);
  }

  private static List<Finding> totalsDeviations(
      MonetaryTotalType monetaryTotal, TaxTotalType taxTotal, Invoice invoice) {
    List<Finding> notes = new ArrayList<>();
    if (monetaryTotal != null) {
      addDeviation(
          notes,
          "cac:LegalMonetaryTotal/cbc:TaxInclusiveAmount",
          monetaryTotal.getTaxInclusiveAmountValue(),
          invoice.totals().grossTotal().amount());
      addDeviation(
          notes,
          "cac:LegalMonetaryTotal/cbc:PayableAmount",
          monetaryTotal.getPayableAmountValue(),
          invoice.totals().payableAmount().amount());
    }
    if (taxTotal != null) {
      addDeviation(
          notes,
          "cac:TaxTotal/cbc:TaxAmount",
          taxTotal.getTaxAmountValue(),
          invoice.totals().taxTotal().amount());
    }
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

  /**
   * The document-level fields both UBL kinds share, normalised so the read body is written once.
   */
  private record Header(
      String id,
      InvoiceTypeCode type,
      LocalDate issueDate,
      LocalDate dueDate,
      String currencyCode,
      String orderReference,
      SupplierPartyType supplier,
      CustomerPartyType customer,
      PeriodType invoicePeriod,
      LocalDate deliveryDate,
      PaymentMeansType paymentMeans,
      String paymentTerms,
      TaxTotalType taxTotal,
      MonetaryTotalType monetaryTotal) {}

  /** One line's fields, normalised across {@code InvoiceLine} and {@code CreditNoteLine}. */
  private record LineData(
      String id,
      String name,
      BigDecimal quantity,
      String unitCode,
      BigDecimal price,
      BigDecimal lineExtensionAmount,
      TaxCategoryType taxCategory) {}
}
