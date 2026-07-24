package com.stoicera.einvoice.validation;

import java.nio.charset.StandardCharsets;

/**
 * Hand-written XML fixtures for the validation tests. Deliberately inline rather than generated via
 * the mapping module: the validation module must never take a main-scope dependency on {@code
 * mapping}, and a hostile reviewer can read exactly what is fed to the pipeline.
 *
 * <p>{@link #validEbInterface61()} is the minimal structurally valid ebInterface 6.1 invoice —
 * every required {@code InvoiceType} child and attribute per the bundled 6.1 XSD, nothing more. The
 * broken and wrong-version variants are derived from it so the difference under test is isolated.
 */
public final class TestDocuments {

  private TestDocuments() {}

  /** Minimal ebInterface 6.1 document that passes the bundled 6.1 XSD without a single error. */
  public static String validEbInterface61() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <Invoice xmlns="http://www.ebinterface.at/schema/6p1/"
                 GeneratingSystem="einvoice-at-tests"
                 DocumentType="Invoice"
                 InvoiceCurrency="EUR"
                 Language="de">
          <InvoiceNumber>993433000298</InvoiceNumber>
          <InvoiceDate>2024-03-05</InvoiceDate>
          <Biller>
            <VATIdentificationNumber>ATU51507409</VATIdentificationNumber>
          </Biller>
          <InvoiceRecipient>
            <VATIdentificationNumber>ATU18708634</VATIdentificationNumber>
          </InvoiceRecipient>
          <Details>
            <ItemList>
              <ListLineItem>
                <Description>Schulungskosten</Description>
                <Quantity Unit="STK">1.0000</Quantity>
                <UnitPrice>100.0000</UnitPrice>
                <TaxItem>
                  <TaxableAmount>100.00</TaxableAmount>
                  <TaxPercent TaxCategoryCode="S">20.00</TaxPercent>
                </TaxItem>
                <LineItemAmount>100.00</LineItemAmount>
              </ListLineItem>
            </ItemList>
          </Details>
          <Tax>
            <TaxItem>
              <TaxableAmount>100.00</TaxableAmount>
              <TaxPercent TaxCategoryCode="S">20.00</TaxPercent>
              <TaxAmount>20.00</TaxAmount>
            </TaxItem>
          </Tax>
          <TotalGrossAmount>120.00</TotalGrossAmount>
          <PayableAmount>120.00</PayableAmount>
        </Invoice>
        """;
  }

  /**
   * ebInterface 6.1 document missing the required {@code InvoiceNumber} — well-formed XML and the
   * right namespace, but structurally invalid against the 6.1 XSD.
   */
  public static String brokenEbInterface61() {
    return validEbInterface61().replaceFirst("  <InvoiceNumber>993433000298</InvoiceNumber>\n", "");
  }

  /**
   * A well-formed document in the ebInterface 6.0 namespace — a recognised but unsupported version.
   */
  public static String ebInterface60() {
    return validEbInterface61()
        .replace("http://www.ebinterface.at/schema/6p1/", "http://www.ebinterface.at/schema/6p0/");
  }

  /** Well-formed XML whose root namespace matches no known invoice format. */
  public static String unknownNamespace() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <purchaseOrder xmlns="urn:example:not-an-invoice">
          <item>widget</item>
        </purchaseOrder>
        """;
  }

  /** Not well-formed XML at all. */
  public static String malformed() {
    return "<Invoice><unclosed>";
  }

  /**
   * The classic XXE probe: a document that declares a {@code DOCTYPE} with an external-file entity.
   * A hardened parser must refuse it outright because it declares a {@code DOCTYPE}.
   */
  public static String xxeDoctypePayload() {
    return """
        <?xml version="1.0"?>
        <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <foo>&xxe;</foo>
        """;
  }

  public static byte[] bytes(String xml) {
    return xml.getBytes(StandardCharsets.UTF_8);
  }
}
