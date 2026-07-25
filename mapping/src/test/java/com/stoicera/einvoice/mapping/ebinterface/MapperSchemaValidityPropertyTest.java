package com.stoicera.einvoice.mapping.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.list.ErrorList;
import com.helger.ebinterface.EbInterface61Marshaller;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.mapping.CanonicalInvoiceArbitraries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property tests for {@link InvoiceToEbInterface61Mapper}. The load-bearing property is <em>schema
 * validity</em>: every canonical invoice the generator can produce must map to an ebInterface 6.1
 * document that re-reads without a single schema error through a schema-validating marshaller. Two
 * structural properties guard the parts the mapper must never drop or recompute: the tax-item count
 * and the payable amount.
 *
 * <p>Field-level value preservation (each amount/percent/code/name copied verbatim) is the separate
 * responsibility of {@link MapperValuePreservationPropertyTest}; both draw from the same {@link
 * CanonicalInvoiceArbitraries} input space.
 *
 * <p>The strategy's own writer is deliberately lenient (schema off); this test re-reads its output
 * with {@code setUseSchema(true)} so the bundled ebInterface 6.1 XSD is the judge, not the writer.
 */
class MapperSchemaValidityPropertyTest {

  private static final InvoiceToEbInterface61Mapper MAPPER = new InvoiceToEbInterface61Mapper();
  private static final EbInterface61Strategy STRATEGY = new EbInterface61Strategy();

  @Property(tries = 300)
  void mappedInvoiceReReadsWithoutSchemaErrors(@ForAll("canonicalInvoices") Invoice invoice) {
    String xml = STRATEGY.write(MAPPER.map(invoice));

    ErrorList errors = new ErrorList();
    Ebi61InvoiceType reread =
        new EbInterface61Marshaller()
            .setUseSchema(true)
            .setCollectErrors(errors)
            .read(xml.getBytes(StandardCharsets.UTF_8));

    assertThat(errors.containsAtLeastOneError())
        .withFailMessage("expected no schema errors but got: %s%nXML:%n%s", messages(errors), xml)
        .isFalse();
    assertThat(reread).isNotNull();
  }

  @Property(tries = 300)
  void taxItemCountEqualsCanonicalBreakdownSize(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);

    assertThat(ebi.getTax().getTaxItemCount()).isEqualTo(invoice.vatBreakdown().size());
  }

  @Property(tries = 300)
  void payableAmountEqualsCanonicalPayable(@ForAll("canonicalInvoices") Invoice invoice) {
    Ebi61InvoiceType ebi = MAPPER.map(invoice);

    assertThat(ebi.getPayableAmount())
        .isEqualByComparingTo(invoice.totals().payableAmount().amount());
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
