package com.stoicera.einvoice.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class ValidationContextTest {

  @Test
  void constructorCopiesInputSoLaterCallerMutationDoesNotAffectParsing() {
    byte[] source = TestDocuments.bytes(TestDocuments.validEbInterface61());
    ValidationContext ctx = new ValidationContext(source);

    source[0] = 0; // corrupt the caller's array after construction

    // The context parsed its own defensive copy, so the mutation cannot reach the DOM.
    assertThat(ctx.dom()).isPresent();
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
