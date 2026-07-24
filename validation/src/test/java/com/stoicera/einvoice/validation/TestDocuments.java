package com.stoicera.einvoice.validation;

import java.nio.charset.StandardCharsets;

/**
 * Hand-written XML fixtures for the validation tests. Deliberately inline rather than generated via
 * the mapping module: the validation module must never take a main-scope dependency on {@code
 * mapping}, and a hostile reviewer can read exactly what is fed to the pipeline.
 *
 * <p>{@link #validEbInterface61()} is the minimal <em>fully AT-B2G-valid</em> ebInterface 6.1
 * invoice: every required {@code InvoiceType} child and attribute per the bundled 6.1 XSD, plus the
 * {@code OrderReference/OrderID} (Auftragsreferenz) the AT-B2G Schematron ({@code AT-B2G-01})
 * requires — nothing more. The broken, wrong-version and missing/blank-order-reference variants are
 * derived from it so the difference under test is isolated.
 */
public final class TestDocuments {

  /**
   * A structurally correct IBAN that passes the mod-97 checksum — the ECB reference example for
   * Austria (see {@code Iban}'s Javadoc).
   */
  public static final String VALID_IBAN = "AT611904300234573201";

  /**
   * The same IBAN with its last check digit flipped: correct shape and length (so it clears the
   * ebInterface 6.1 XSD, which only bounds the length) but a failing mod-97 checksum — the {@code
   * AT-B2G-02} case.
   */
  public static final String CHECKSUM_BROKEN_IBAN = "AT611904300234573202";

  private TestDocuments() {}

  /**
   * Minimal ebInterface 6.1 document that passes the bundled 6.1 XSD <em>and</em> the AT-B2G
   * Schematron without a single error — it carries the Auftragsreferenz {@code AT-B2G-01} demands.
   */
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
            <OrderReference>
              <OrderID>4021-2024</OrderID>
            </OrderReference>
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
   * Length, in characters, of the offending value embedded by {@link
   * #ebInterface61WithOverlongXsdValue()}. Chosen well above the core {@code Finding}'s
   * 4096-character message cap so that an <em>unbounded</em> echo of the value into the finding
   * text would overflow that cap and throw — the P1-2 crash this fixture reproduces.
   */
  public static final int OVERLONG_VALUE_LENGTH = 5000;

  /**
   * A well-formed ebInterface 6.1 document whose {@code InvoiceDate} carries a {@value
   * #OVERLONG_VALUE_LENGTH}-character value that is not a valid {@code xs:date}. Xerces bakes the
   * whole offending value into its {@code cvc-datatype-valid} message, so the XSD stage builds a
   * finding whose detail text is longer than the {@code Finding} message cap unless the foreign
   * text is bounded first. The namespace is untouched, so the document clears format detection and
   * reaches the XSD stage.
   */
  public static String ebInterface61WithOverlongXsdValue() {
    return validEbInterface61().replace("2024-03-05", "9".repeat(OVERLONG_VALUE_LENGTH));
  }

  /**
   * ebInterface 6.1 document that is fully XSD-valid but carries no {@code OrderReference} — the
   * business-rule case the AT-B2G Schematron must catch as {@code AT-B2G-01} (Auftragsreferenz
   * missing). Derived by removing the whole {@code OrderReference} element from the valid document.
   */
  public static String ebInterface61WithoutOrderReference() {
    return validEbInterface61().replaceFirst("(?s)\\s*<OrderReference>.*?</OrderReference>", "");
  }

  /**
   * ebInterface 6.1 document whose {@code OrderID} is whitespace only — XSD-valid ({@code IDType}
   * is an unconstrained string), but the AT-B2G Schematron still fails {@code AT-B2G-01} because
   * {@code normalize-space()} of the order id is empty. Derived by blanking the valid order id.
   */
  public static String ebInterface61BlankOrderReference() {
    return validEbInterface61().replace("4021-2024", "   ");
  }

  /**
   * The valid AT-B2G document plus a {@code PaymentMethod/UniversalBankTransaction} carrying a
   * single beneficiary account whose {@code IBAN} passes the mod-97 checksum — fully compliant, the
   * {@code AT-B2G-02} happy path.
   */
  public static String validEbInterface61WithValidIban() {
    return withBeneficiaryAccounts(validEbInterface61(), VALID_IBAN);
  }

  /**
   * The valid AT-B2G document plus one beneficiary account whose {@code IBAN} fails the mod-97
   * checksum — XSD- and Schematron-clean, so the only finding is {@code AT-B2G-02}.
   */
  public static String ebInterface61WithBrokenIban() {
    return withBeneficiaryAccounts(validEbInterface61(), CHECKSUM_BROKEN_IBAN);
  }

  /**
   * Two beneficiary accounts, the first valid and the second checksum-broken — proves the finding
   * names the offending account by its 1-based position (account 2), not the first one.
   */
  public static String ebInterface61WithSecondAccountBrokenIban() {
    return withBeneficiaryAccounts(validEbInterface61(), VALID_IBAN, CHECKSUM_BROKEN_IBAN);
  }

  /**
   * A {@code PaymentMethod/UniversalBankTransaction} whose single beneficiary account carries no
   * {@code IBAN} element at all (only a {@code BankAccountOwner}). {@code AT-B2G-02} checks the
   * IBANs that are present; a missing IBAN is the XSD's concern, not this rule's, so this yields
   * nothing.
   */
  public static String ebInterface61WithBeneficiaryAccountButNoIban() {
    return withPaymentMethod(
        validEbInterface61(),
        """
              <BeneficiaryAccount>
                <BankAccountOwner>Muster GmbH</BankAccountOwner>
              </BeneficiaryAccount>
        """);
  }

  /**
   * Violates <em>both</em> Austrian business rules at once: no {@code OrderReference} ({@code
   * AT-B2G-01}) and a checksum-broken {@code IBAN} ({@code AT-B2G-02}). Still XSD-valid, so both
   * the Schematron and the business-rule stage run and each contributes its finding.
   */
  public static String ebInterface61ViolatingBothAtRules() {
    return withBeneficiaryAccounts(ebInterface61WithoutOrderReference(), CHECKSUM_BROKEN_IBAN);
  }

  private static String withBeneficiaryAccounts(String base, String... ibans) {
    StringBuilder accounts = new StringBuilder();
    for (String iban : ibans) {
      accounts
          .append("      <BeneficiaryAccount>\n")
          .append("        <IBAN>")
          .append(iban)
          .append("</IBAN>\n")
          .append("      </BeneficiaryAccount>\n");
    }
    return withPaymentMethod(base, accounts.toString());
  }

  private static String withPaymentMethod(String base, String beneficiaryAccounts) {
    return base.replace(
        "  <PayableAmount>120.00</PayableAmount>\n",
        "  <PayableAmount>120.00</PayableAmount>\n"
            + "  <PaymentMethod>\n"
            + "    <UniversalBankTransaction>\n"
            + beneficiaryAccounts
            + "    </UniversalBankTransaction>\n"
            + "  </PaymentMethod>\n");
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
