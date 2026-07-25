package com.stoicera.einvoice.mapping.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.ebinterface.v61.Ebi61AddressType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61ListLineItemType;
import com.helger.ebinterface.v61.Ebi61TaxItemType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.ServicePeriod;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.tax.VatBreakdownEntry;
import com.stoicera.einvoice.mapping.CanonicalInvoiceArbitraries;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Value-preservation properties for {@link InvoiceToEbInterface61Mapper}: the mapper performs no
 * arithmetic, so every value it carries must appear <em>verbatim</em> in the mapped ebInterface 6.1
 * tree. This is the falsifiable correctness backbone the schema-validity property alone cannot
 * provide (finding C2 — schema validity, tax-item count and payable amount all survive a swapped or
 * mangled field). Falsifiability is recorded in the M2 fix-wave report: temporarily swapping {@code
 * TaxableAmount}/{@code TaxAmount} in {@code mapTax} turns {@link #breakdownEntriesMapVerbatim}
 * red.
 *
 * <p>The canonical model derives the VAT breakdown from the lines and preserves order, and the
 * mapper iterates both lists in order, so document tax item {@code j} corresponds to breakdown
 * entry {@code j} and list line item {@code i} to line {@code i} — the correspondence the per-index
 * assertions below rely on.
 */
class MapperValuePreservationPropertyTest {

  private static final InvoiceToEbInterface61Mapper MAPPER = new InvoiceToEbInterface61Mapper();

  /** The e-rechnung.gv.at placeholder the mapper substitutes for a party without a UID (A1). */
  private static final String NO_UID_CONVENTION = "ATU00000000";

  @Property(tries = 300)
  void breakdownEntriesMapVerbatim(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);
    List<VatBreakdownEntry> breakdown = invoice.vatBreakdown();

    assertThat(ebi.getTax().getTaxItemCount()).isEqualTo(breakdown.size());
    for (int j = 0; j < breakdown.size(); j++) {
      VatBreakdownEntry entry = breakdown.get(j);
      Ebi61TaxItemType taxItem = ebi.getTax().getTaxItemAtIndex(j);

      assertThat(taxItem.getTaxableAmount())
          .as("breakdown[%d] TaxableAmount", j)
          .isEqualByComparingTo(entry.taxableAmount().amount());
      assertThat(taxItem.getTaxAmount())
          .as("breakdown[%d] TaxAmount", j)
          .isEqualByComparingTo(entry.taxAmount().amount());
      assertThat(taxItem.getTaxPercent().getValue())
          .as("breakdown[%d] TaxPercent", j)
          .isEqualByComparingTo(entry.rate().percentage());
      assertThat(taxItem.getTaxPercent().getTaxCategoryCode())
          .as("breakdown[%d] TaxCategoryCode", j)
          .isEqualTo(entry.rate().category().code());
    }
  }

  @Property(tries = 300)
  void linesMapVerbatim(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);
    List<InvoiceLine> lines = invoice.lines();
    List<Ebi61ListLineItemType> items = ebi.getDetails().getItemListAtIndex(0).getListLineItem();

    assertThat(items).hasSameSizeAs(lines);
    for (int i = 0; i < lines.size(); i++) {
      InvoiceLine line = lines.get(i);
      Ebi61ListLineItemType item = items.get(i);

      assertThat(item.getQuantity().getValue())
          .as("line[%d] Quantity", i)
          .isEqualByComparingTo(line.quantity());
      assertThat(item.getQuantity().getUnit()).as("line[%d] @Unit", i).isEqualTo(line.unitCode());
      assertThat(item.getUnitPrice().getValue())
          .as("line[%d] UnitPrice", i)
          .isEqualByComparingTo(line.unitPrice());
      assertThat(item.getDescription())
          .as("line[%d] Description", i)
          .containsExactly(line.description());
      assertThat(item.getLineItemAmount())
          .as("line[%d] LineItemAmount", i)
          .isEqualByComparingTo(line.netAmount(invoice.currency()).amount());
    }
  }

  @Property(tries = 300)
  void partiesMapVerbatim(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);

    assertThat(ebi.getBiller().getVATIdentificationNumber())
        .as("Biller VATIdentificationNumber")
        .isEqualTo(expectedVatId(invoice.seller().vatId()));
    assertThat(ebi.getBiller().getAddress().getName())
        .as("Biller Address/Name")
        .isEqualTo(invoice.seller().name());

    assertThat(ebi.getInvoiceRecipient().getVATIdentificationNumber())
        .as("InvoiceRecipient VATIdentificationNumber")
        .isEqualTo(expectedVatId(invoice.buyer().vatId()));
    assertThat(ebi.getInvoiceRecipient().getAddress().getName())
        .as("InvoiceRecipient Address/Name")
        .isEqualTo(invoice.buyer().name());
  }

  /** The canonical VAT id verbatim, or the no-UID convention when the party carries none. */
  private static String expectedVatId(String canonicalVatId) {
    return canonicalVatId != null ? canonicalVatId : NO_UID_CONVENTION;
  }

  @Property(tries = 300)
  void deliveryInfoMapsVerbatim(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);

    if (invoice.deliveryDate().isPresent()) {
      assertThat(ebi.getDelivery()).as("Delivery present for a deliveryDate invoice").isNotNull();
      assertThat(ebi.getDelivery().getDateLocal())
          .as("Delivery/Date")
          .isEqualTo(invoice.deliveryDate().get());
      assertThat(ebi.getDelivery().getPeriod())
          .as("Delivery/Period absent when Date is present")
          .isNull();
    } else if (invoice.servicePeriod().isPresent()) {
      ServicePeriod period = invoice.servicePeriod().get();
      assertThat(ebi.getDelivery()).as("Delivery present for a servicePeriod invoice").isNotNull();
      assertThat(ebi.getDelivery().getDate())
          .as("Delivery/Date absent when Period is present")
          .isNull();
      assertThat(ebi.getDelivery().getPeriod()).as("Delivery/Period").isNotNull();
      assertThat(ebi.getDelivery().getPeriod().getFromDateLocal())
          .as("Delivery/Period/FromDate")
          .isEqualTo(period.from());
      assertThat(ebi.getDelivery().getPeriod().getToDateLocal())
          .as("Delivery/Period/ToDate")
          .isEqualTo(period.to());
    } else {
      assertThat(ebi.getDelivery()).as("Delivery absent when neither is present").isNull();
    }
  }

  @Property(tries = 300)
  void partyEmailsMapVerbatim(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);

    assertPartyEmail(invoice.seller(), ebi.getBiller().getAddress(), "Biller");
    assertPartyEmail(invoice.buyer(), ebi.getInvoiceRecipient().getAddress(), "InvoiceRecipient");
  }

  private static void assertPartyEmail(Party party, Ebi61AddressType address, String label) {
    if (party.email().isPresent()) {
      assertThat(address.getEmail())
          .as("%s Address/Email", label)
          .containsExactly(party.email().get());
    } else {
      assertThat(address.hasNoEmailEntries())
          .as("%s Address/Email absent when the party carries none", label)
          .isTrue();
    }
  }

  @Provide
  Arbitrary<Invoice> canonicalInvoices() {
    return CanonicalInvoiceArbitraries.canonicalInvoices();
  }
}
