package com.stoicera.einvoice.validation;

/**
 * The single registry of the validator's rule ids — the fixed public contract ADR-0004 §7 defines
 * and that other modules (the corpus, the CLI) depend on. Each id is produced by exactly one
 * pipeline stage; co-locating the whole set here (rather than scattering the constants across the
 * five producing classes) gives a reader one place to see the contract, and keeps the naming scheme
 * uniform.
 *
 * <p>The scheme is {@code PREFIX-NN}: the prefix names the layer a violation belongs to (input XML,
 * format detection, XSD schema, Austrian B2G profile) and the two-digit number distinguishes rules
 * within that layer. Every id follows it, so a report column reads as one convention.
 *
 * <table>
 *   <caption>Rule-id registry</caption>
 *   <tr><th>Id</th><th>Severity</th><th>Producing stage</th><th>Meaning</th></tr>
 *   <tr><td>{@value #XML_01}</td><td>ERROR</td><td>secure DOM parse</td>
 *       <td>the upload is not well-formed XML</td></tr>
 *   <tr><td>{@value #XML_02}</td><td>ERROR</td><td>input-size guard</td>
 *       <td>the upload exceeds the module's defensive input-size cap</td></tr>
 *   <tr><td>{@value #FORMAT_01}</td><td>ERROR</td><td>format detection</td>
 *       <td>the root namespace matches no supported invoice format</td></tr>
 *   <tr><td>{@value #FORMAT_02}</td><td>ERROR</td><td>format detection</td>
 *       <td>a recognised but unsupported ebInterface version</td></tr>
 *   <tr><td>{@value #XSD_01}</td><td>ERROR</td><td>XSD (phive)</td>
 *       <td>the document violates the ebInterface 6.1 schema</td></tr>
 *   <tr><td>{@value #AT_B2G_01}</td><td>ERROR</td><td>AT-B2G Schematron</td>
 *       <td>the Auftragsreferenz ({@code OrderReference/OrderID}) is missing</td></tr>
 *   <tr><td>{@value #AT_B2G_02}</td><td>ERROR</td><td>AT-B2G business rule</td>
 *       <td>a present beneficiary-account IBAN fails the mod-97 checksum</td></tr>
 *   <tr><td>{@value #AT_B2G_03}</td><td>ERROR</td><td>AT-B2G Schematron</td>
 *       <td>the Biller e-mail address ({@code Biller/Address/Email}) is missing</td></tr>
 *   <tr><td>{@value #AT_B2G_04}</td><td>ERROR</td><td>AT-B2G Schematron</td>
 *       <td>the Lieferantennummer ({@code Biller/InvoiceRecipientsBillerID}) is missing</td></tr>
 *   <tr><td>{@value #AT_B2G_05}</td><td>ERROR</td><td>AT-B2G Schematron</td>
 *       <td>no {@code PaymentMethod} ({@code UniversalBankTransaction} or {@code NoPayment}) is
 *       present</td></tr>
 * </table>
 */
public final class RuleIds {

  /** The upload is not well-formed XML. */
  public static final String XML_01 = "XML-01";

  /** The upload exceeds the module's defensive input-size cap. */
  public static final String XML_02 = "XML-02";

  /** The document is XML but its namespace matches no supported invoice format. */
  public static final String FORMAT_01 = "FORMAT-01";

  /** The document is ebInterface, but a version this platform does not support. */
  public static final String FORMAT_02 = "FORMAT-02";

  /** The document violates the ebInterface 6.1 XML schema. */
  public static final String XSD_01 = "XSD-01";

  /** A B2G invoice must carry an Auftragsreferenz ({@code OrderReference/OrderID}). */
  public static final String AT_B2G_01 = "AT-B2G-01";

  /** Every present beneficiary-account IBAN must pass the mod-97 checksum. */
  public static final String AT_B2G_02 = "AT-B2G-02";

  /** A B2G invoice must carry a Biller e-mail address ({@code Biller/Address/Email}). */
  public static final String AT_B2G_03 = "AT-B2G-03";

  /**
   * A B2G invoice must carry the Biller's Lieferantennummer ({@code
   * Biller/InvoiceRecipientsBillerID}).
   */
  public static final String AT_B2G_04 = "AT-B2G-04";

  /**
   * A B2G invoice must carry a {@code PaymentMethod} ({@code UniversalBankTransaction} or {@code
   * NoPayment}).
   */
  public static final String AT_B2G_05 = "AT-B2G-05";

  private RuleIds() {}
}
