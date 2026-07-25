package com.stoicera.einvoice.mapping.ubl;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.list.ErrorList;
import com.helger.ubl21.UBL21Marshaller;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.formats.ubl.Ubl21CreditNoteStrategy;
import com.stoicera.einvoice.formats.ubl.Ubl21InvoiceStrategy;
import com.stoicera.einvoice.mapping.CanonicalInvoiceArbitraries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property tests for {@link InvoiceToUblMapper}, mirroring the ebInterface mapper's suite.
 *
 * <p>The load-bearing property is <em>schema validity</em>: every canonical invoice the generator
 * can produce must map to a UBL document that re-reads without a single schema error through a
 * schema-validating marshaller. The strategies' own writers are deliberately lenient (schema off);
 * this test re-reads their output with {@code setUseSchema(true)} so the bundled UBL 2.1 XSDs are
 * the judge, not the writer.
 *
 * <p>Schema validity is a weaker bar for UBL than it is for ebInterface, and deliberately so: the
 * UBL 2.1 schema permits almost everything, and what actually constrains a Peppol document is the
 * EN 16931 / BIS Schematron. That rule set is not run here — it belongs to the {@code validation}
 * module, which owns the pinned OpenPeppol artefacts — so this property proves the tree is
 * well-formed and correctly typed, and the corpus in {@code validation} proves it is conformant.
 * Saying so plainly matters more than an impressive-sounding claim.
 *
 * <p>Both document kinds are exercised from one input space by mapping every generated invoice as
 * whichever kind its BT-3 type code calls for; {@link CanonicalInvoiceArbitraries} draws both
 * codes.
 */
class UblMapperSchemaValidityPropertyTest {

  private static final InvoiceToUblMapper MAPPER = new InvoiceToUblMapper();
  private static final Ubl21InvoiceStrategy INVOICES = new Ubl21InvoiceStrategy();
  private static final Ubl21CreditNoteStrategy CREDIT_NOTES = new Ubl21CreditNoteStrategy();

  @Property(tries = 200)
  void mappedDocumentReReadsWithoutSchemaErrors(@ForAll("canonicalInvoices") Invoice invoice) {
    String xml = write(MAPPER.map(invoice));

    ErrorList errors = new ErrorList();
    Object reread =
        switch (MAPPER.map(invoice)) {
          case UblDocument.CommercialInvoice ignored ->
              UBL21Marshaller.invoice()
                  .setUseSchema(true)
                  .setCollectErrors(errors)
                  .read(xml.getBytes(StandardCharsets.UTF_8));
          case UblDocument.CreditNote ignored ->
              UBL21Marshaller.creditNote()
                  .setUseSchema(true)
                  .setCollectErrors(errors)
                  .read(xml.getBytes(StandardCharsets.UTF_8));
        };

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", messages(errors), xml)
        .isFalse();
    assertThat(reread).isNotNull();
  }

  /** The root element must follow BT-3, since that is the only thing that selects it. */
  @Property(tries = 200)
  void documentKindFollowsTheTypeCode(@ForAll("canonicalInvoices") Invoice invoice) {
    UblDocument document = MAPPER.map(invoice);

    if (invoice.type() == InvoiceTypeCode.COMMERCIAL_INVOICE) {
      assertThat(document).isInstanceOf(UblDocument.CommercialInvoice.class);
    } else {
      assertThat(document).isInstanceOf(UblDocument.CreditNote.class);
    }
  }

  /** The breakdown must survive one-for-one — the mapper never merges or drops a subtotal. */
  @Property(tries = 200)
  void taxSubtotalCountEqualsCanonicalBreakdownSize(@ForAll("canonicalInvoices") Invoice invoice) {
    int expected = invoice.vatBreakdown().size();

    switch (MAPPER.map(invoice)) {
      case UblDocument.CommercialInvoice(var document) ->
          assertThat(document.getTaxTotal().getFirst().getTaxSubtotalCount()).isEqualTo(expected);
      case UblDocument.CreditNote(var document) ->
          assertThat(document.getTaxTotal().getFirst().getTaxSubtotalCount()).isEqualTo(expected);
    }
  }

  /** Payable amount is copied, never recomputed (ADR-0003). */
  @Property(tries = 200)
  void payableAmountEqualsCanonicalPayable(@ForAll("canonicalInvoices") Invoice invoice) {
    var expected = invoice.totals().payableAmount().amount();

    switch (MAPPER.map(invoice)) {
      case UblDocument.CommercialInvoice(var document) ->
          assertThat(document.getLegalMonetaryTotal().getPayableAmountValue())
              .isEqualByComparingTo(expected);
      case UblDocument.CreditNote(var document) ->
          assertThat(document.getLegalMonetaryTotal().getPayableAmountValue())
              .isEqualByComparingTo(expected);
    }
  }

  private static String write(UblDocument document) {
    return switch (document) {
      case UblDocument.CommercialInvoice(var invoice) -> INVOICES.write(invoice);
      case UblDocument.CreditNote(var creditNote) -> CREDIT_NOTES.write(creditNote);
    };
  }

  private static List<String> messages(ErrorList errors) {
    List<String> out = new ArrayList<>();
    for (IError error : errors) {
      out.add(error.getAsStringLocaleIndepdent());
    }
    return out;
  }

  @Provide
  Arbitrary<Invoice> canonicalInvoices() {
    return CanonicalInvoiceArbitraries.canonicalInvoices();
  }
}
