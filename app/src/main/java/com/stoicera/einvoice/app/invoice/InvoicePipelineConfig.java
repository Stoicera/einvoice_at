package com.stoicera.einvoice.app.invoice;

import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.mapping.ebinterface.InvoiceToEbInterface61Mapper;
import com.stoicera.einvoice.mapping.json.InvoiceJsonReader;
import com.stoicera.einvoice.validation.InvoiceValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the library generation/validation chain as application singletons.
 *
 * <p>These four classes live in Spring-free packages outside {@code com.stoicera.einvoice.app}, so
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

  @Bean
  InvoiceValidator ebInterface61Validator() {
    return new InvoiceValidator();
  }
}
