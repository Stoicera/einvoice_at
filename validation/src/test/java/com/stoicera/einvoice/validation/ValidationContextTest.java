package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.ebinterface.EEbInterfaceVersion;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class ValidationContextTest {

  @Test
  void xmlReturnsDefensiveCopy() {
    byte[] source = TestDocuments.bytes(TestDocuments.validEbInterface61());
    ValidationContext ctx = new ValidationContext(source);

    byte[] first = ctx.xml();
    first[0] = 0;

    assertThat(ctx.xml()).isEqualTo(source).isNotSameAs(first);
  }

  @Test
  void domIsParsedOnceAndCached() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    Document first = ctx.dom().orElseThrow();
    Document second = ctx.dom().orElseThrow();

    assertThat(second).isSameAs(first);
  }

  @Test
  void domIsEmptyForMalformedInput() {
    ValidationContext ctx = new ValidationContext(TestDocuments.bytes(TestDocuments.malformed()));

    assertThat(ctx.dom()).isEmpty();
  }

  @Test
  void detectedVersionRoundTrips() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(ctx.detectedVersion()).isEmpty();
    ctx.setDetectedVersion(EEbInterfaceVersion.V61);
    assertThat(ctx.detectedVersion()).contains(EEbInterfaceVersion.V61);
  }

  @Test
  void ebiInvoiceIsParsedOnceForValidDocument() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    assertThat(ctx.ebiInvoice()).isPresent();
    assertThat(ctx.ebiInvoice().orElseThrow()).isSameAs(ctx.ebiInvoice().orElseThrow());
  }

  @Test
  void ebiInvoiceIsEmptyForMalformedInput() {
    ValidationContext ctx = new ValidationContext(TestDocuments.bytes(TestDocuments.malformed()));

    assertThat(ctx.ebiInvoice()).isEmpty();
  }
}
