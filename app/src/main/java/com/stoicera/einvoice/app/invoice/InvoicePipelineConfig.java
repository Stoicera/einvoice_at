package com.stoicera.einvoice.app.invoice;

import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.formats.ubl.Ubl21CreditNoteStrategy;
import com.stoicera.einvoice.formats.ubl.Ubl21InvoiceStrategy;
import com.stoicera.einvoice.mapping.ebinterface.EbInterface61ToInvoiceMapper;
import com.stoicera.einvoice.mapping.ebinterface.InvoiceToEbInterface61Mapper;
import com.stoicera.einvoice.mapping.json.InvoiceJsonReader;
import com.stoicera.einvoice.mapping.ubl.InvoiceToUblMapper;
import com.stoicera.einvoice.mapping.ubl.UblToInvoiceMapper;
import com.stoicera.einvoice.rendering.InvoicePdfRenderer;
import com.stoicera.einvoice.validation.InvoiceValidator;
import com.stoicera.einvoice.validation.ValidationObserver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the library generation/validation chain as application singletons.
 *
 * <p>These classes live in Spring-free packages outside {@code com.stoicera.einvoice.app}, so
 * component scanning never sees them; they are instantiated explicitly here instead. Each is
 * documented as stateless and thread-safe, so a single shared instance is correct, and building the
 * (comparatively heavy) validator once at startup — it precompiles the AT-B2G Schematron — keeps
 * that cost off the request path.
 */
@Configuration
class InvoicePipelineConfig {

  @Bean
  InvoiceJsonReader invoiceJsonReader() {
    return new InvoiceJsonReader();
  }

  @Bean
  InvoiceToEbInterface61Mapper invoiceToEbInterface61Mapper() {
    return new InvoiceToEbInterface61Mapper();
  }

  @Bean
  EbInterface61Strategy ebInterface61Strategy() {
    return new EbInterface61Strategy();
  }

  // Named for what it is, not for the one format it used to handle. The bean was still called
  // `ebInterface61Validator` after M4 gave it the UBL pipeline too — a stale name on a shared
  // singleton, which is how a reader ends up believing the public validator is ebInterface-only
  // (M4 hostile review, finding F14).
  //
  // The observer is injected rather than defaulted so that EVERY validation this application runs —
  // the public validator, the API, the dashboard, invoice creation, conversion — is traced through
  // one instance. A no-argument `new InvoiceValidator()` here would have left the pipeline stages
  // invisible while the M6 documentation claimed otherwise (ADR-0012).
  @Bean
  InvoiceValidator invoiceValidator(ValidationObserver observer) {
    return new InvoiceValidator(observer);
  }

  @Bean
  Ubl21InvoiceStrategy ubl21InvoiceStrategy() {
    return new Ubl21InvoiceStrategy();
  }

  @Bean
  Ubl21CreditNoteStrategy ubl21CreditNoteStrategy() {
    return new Ubl21CreditNoteStrategy();
  }

  @Bean
  InvoiceToUblMapper invoiceToUblMapper() {
    return new InvoiceToUblMapper();
  }

  @Bean
  UblToInvoiceMapper ublToInvoiceMapper() {
    return new UblToInvoiceMapper();
  }

  @Bean
  EbInterface61ToInvoiceMapper ebInterface61ToInvoiceMapper() {
    return new EbInterface61ToInvoiceMapper();
  }

  @Bean
  InvoicePdfRenderer invoicePdfRenderer() {
    return new InvoicePdfRenderer();
  }
}
