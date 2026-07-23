package com.stoicera.einvoice.core.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvoiceTypeCodeTest {

  @Test
  void carriesUntdid1001Codes() {
    assertThat(InvoiceTypeCode.COMMERCIAL_INVOICE.code()).isEqualTo("380");
    assertThat(InvoiceTypeCode.CREDIT_NOTE.code()).isEqualTo("381");
  }
}
