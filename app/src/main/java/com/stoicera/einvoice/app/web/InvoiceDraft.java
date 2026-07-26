package com.stoicera.einvoice.app.web;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A half-finished invoice, as the wizard collects it across four steps.
 *
 * <p>Mutable and deliberately dumb: it validates nothing beyond "is this field filled in", because
 * the real invariants live in {@code core} and re-implementing them here would give the platform
 * two definitions of a valid invoice — the failure mode the whole canonical model exists to
 * prevent. What this class owns is the shape of the JSON it hands to {@code InvoiceJsonReader}, and
 * one job: {@link #toCanonicalJson()}.
 *
 * <p>{@link Serializable} because it is stored in the HTTP session — a session may be serialized by
 * the container, and a draft that could not survive that would fail only under a configuration
 * nobody tests locally.
 *
 * <p><strong>The JSON is built with Jackson's node API, not with string concatenation.</strong>
 * Every field here came from a text input, so a quote or a backslash in a company name would
 * produce malformed JSON — and the reader would report a shape error the user cannot connect to
 * what they typed. Jackson escapes it; there is no reason to hand-roll that.
 */
public final class InvoiceDraft implements Serializable {

  private static final long serialVersionUID = 1L;

  /** One collected invoice line, still as typed. */
  public record Line(
      String description,
      String quantity,
      String unitCode,
      String unitPrice,
      String vatCategory,
      String vatPercent)
      implements Serializable {}

  // Step 1 — Kopfdaten
  private String invoiceNumber = "";
  private String type = "INVOICE";
  private String issueDate = "";
  private String dueDate = "";
  private String deliveryDate = "";
  private String currency = "EUR";
  private String orderReference = "";
  private String supplierNumber = "";

  // Step 2 — Parteien
  private String sellerName = "";
  private String sellerVatId = "";
  private String sellerEmail = "";
  private String sellerStreet = "";
  private String sellerCity = "";
  private String sellerPostalCode = "";
  private String sellerCountryCode = "AT";
  private String buyerName = "";
  private String buyerVatId = "";
  private String buyerStreet = "";
  private String buyerCity = "";
  private String buyerPostalCode = "";
  private String buyerCountryCode = "AT";

  // Step 3 — Positionen
  private final List<Line> lines = new ArrayList<>();

  // Step 4 — Zahlung
  private String iban = "";
  private String bic = "";
  private String paymentTerms = "";

  /**
   * Serialises the draft into the canonical-invoice JSON {@code InvoiceJsonReader} consumes.
   *
   * <p>Optional fields are omitted rather than sent empty: the reader treats a missing {@code
   * dueDate} as absent and an empty string as an unparsable date, so writing {@code ""} would turn
   * "the user left it blank" into a shape error.
   */
  public String toCanonicalJson() {
    JsonMapper mapper = JsonMapper.builder().build();
    ObjectNode root = mapper.createObjectNode();
    root.put("invoiceNumber", invoiceNumber.trim());
    root.put("type", type);
    root.put("issueDate", issueDate.trim());
    putIfPresent(root, "dueDate", dueDate);
    putIfPresent(root, "deliveryDate", deliveryDate);
    root.put("currency", currency.trim());
    putIfPresent(root, "orderReference", orderReference);
    putIfPresent(root, "supplierNumber", supplierNumber);

    root.set(
        "seller",
        party(
            mapper,
            sellerName,
            sellerVatId,
            sellerEmail,
            sellerStreet,
            sellerCity,
            sellerPostalCode,
            sellerCountryCode));
    root.set(
        "buyer",
        party(
            mapper,
            buyerName,
            buyerVatId,
            null,
            buyerStreet,
            buyerCity,
            buyerPostalCode,
            buyerCountryCode));

    ArrayNode lineNodes = root.putArray("lines");
    for (int i = 0; i < lines.size(); i++) {
      Line line = lines.get(i);
      ObjectNode node = lineNodes.addObject();
      // The line id is positional and generated here: EN 16931's BT-126 must be unique within the
      // document, and asking a user to invent one is asking them to get it wrong.
      node.put("id", String.valueOf(i + 1));
      node.put("description", line.description().trim());
      node.put("quantity", line.quantity().trim());
      node.put("unitCode", line.unitCode().trim());
      node.put("unitPrice", line.unitPrice().trim());
      node.put("vatCategory", line.vatCategory());
      node.put("vatPercent", line.vatPercent().trim());
    }

    if (!iban.isBlank()) {
      ObjectNode means = root.putObject("paymentMeans");
      means.put("iban", iban.trim().replace(" ", ""));
      putIfPresent(means, "bic", bic);
    }
    putIfPresent(root, "paymentTerms", paymentTerms);

    return root.toString();
  }

  private static ObjectNode party(
      JsonMapper mapper,
      String name,
      String vatId,
      String email,
      String street,
      String city,
      String postalCode,
      String countryCode) {
    ObjectNode party = mapper.createObjectNode();
    party.put("name", name.trim());
    putIfPresent(party, "vatId", vatId);
    putIfPresent(party, "email", email);
    ObjectNode address = party.putObject("address");
    address.put("street", street.trim());
    address.put("city", city.trim());
    address.put("postalCode", postalCode.trim());
    address.put("countryCode", countryCode.trim());
    return party;
  }

  private static void putIfPresent(ObjectNode node, String field, String value) {
    if (value != null && !value.isBlank()) {
      node.put(field, value.trim());
    }
  }

  public void addLine(Line line) {
    lines.add(line);
  }

  /** Removes the line at {@code index}, ignoring an index that is not there. */
  public void removeLine(int index) {
    if (index >= 0 && index < lines.size()) {
      lines.remove(index);
    }
  }

  public List<Line> getLines() {
    return List.copyOf(lines);
  }

  public boolean hasLines() {
    return !lines.isEmpty();
  }

  // --- plain accessors; Thymeleaf reads these to re-fill each step's form ---------------------

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public void setInvoiceNumber(String invoiceNumber) {
    this.invoiceNumber = orEmpty(invoiceNumber);
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = orEmpty(type).isBlank() ? "INVOICE" : type;
  }

  public String getIssueDate() {
    return issueDate;
  }

  public void setIssueDate(String issueDate) {
    this.issueDate = orEmpty(issueDate);
  }

  public String getDueDate() {
    return dueDate;
  }

  public void setDueDate(String dueDate) {
    this.dueDate = orEmpty(dueDate);
  }

  public String getDeliveryDate() {
    return deliveryDate;
  }

  public void setDeliveryDate(String deliveryDate) {
    this.deliveryDate = orEmpty(deliveryDate);
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = orEmpty(currency).isBlank() ? "EUR" : currency;
  }

  public String getOrderReference() {
    return orderReference;
  }

  public void setOrderReference(String orderReference) {
    this.orderReference = orEmpty(orderReference);
  }

  public String getSupplierNumber() {
    return supplierNumber;
  }

  public void setSupplierNumber(String supplierNumber) {
    this.supplierNumber = orEmpty(supplierNumber);
  }

  public String getSellerName() {
    return sellerName;
  }

  public void setSellerName(String sellerName) {
    this.sellerName = orEmpty(sellerName);
  }

  public String getSellerVatId() {
    return sellerVatId;
  }

  public void setSellerVatId(String sellerVatId) {
    this.sellerVatId = orEmpty(sellerVatId);
  }

  public String getSellerEmail() {
    return sellerEmail;
  }

  public void setSellerEmail(String sellerEmail) {
    this.sellerEmail = orEmpty(sellerEmail);
  }

  public String getSellerStreet() {
    return sellerStreet;
  }

  public void setSellerStreet(String sellerStreet) {
    this.sellerStreet = orEmpty(sellerStreet);
  }

  public String getSellerCity() {
    return sellerCity;
  }

  public void setSellerCity(String sellerCity) {
    this.sellerCity = orEmpty(sellerCity);
  }

  public String getSellerPostalCode() {
    return sellerPostalCode;
  }

  public void setSellerPostalCode(String sellerPostalCode) {
    this.sellerPostalCode = orEmpty(sellerPostalCode);
  }

  public String getSellerCountryCode() {
    return sellerCountryCode;
  }

  public void setSellerCountryCode(String sellerCountryCode) {
    this.sellerCountryCode = orEmpty(sellerCountryCode).isBlank() ? "AT" : sellerCountryCode;
  }

  public String getBuyerName() {
    return buyerName;
  }

  public void setBuyerName(String buyerName) {
    this.buyerName = orEmpty(buyerName);
  }

  public String getBuyerVatId() {
    return buyerVatId;
  }

  public void setBuyerVatId(String buyerVatId) {
    this.buyerVatId = orEmpty(buyerVatId);
  }

  public String getBuyerStreet() {
    return buyerStreet;
  }

  public void setBuyerStreet(String buyerStreet) {
    this.buyerStreet = orEmpty(buyerStreet);
  }

  public String getBuyerCity() {
    return buyerCity;
  }

  public void setBuyerCity(String buyerCity) {
    this.buyerCity = orEmpty(buyerCity);
  }

  public String getBuyerPostalCode() {
    return buyerPostalCode;
  }

  public void setBuyerPostalCode(String buyerPostalCode) {
    this.buyerPostalCode = orEmpty(buyerPostalCode);
  }

  public String getBuyerCountryCode() {
    return buyerCountryCode;
  }

  public void setBuyerCountryCode(String buyerCountryCode) {
    this.buyerCountryCode = orEmpty(buyerCountryCode).isBlank() ? "AT" : buyerCountryCode;
  }

  public String getIban() {
    return iban;
  }

  public void setIban(String iban) {
    this.iban = orEmpty(iban);
  }

  public String getBic() {
    return bic;
  }

  public void setBic(String bic) {
    this.bic = orEmpty(bic);
  }

  public String getPaymentTerms() {
    return paymentTerms;
  }

  public void setPaymentTerms(String paymentTerms) {
    this.paymentTerms = orEmpty(paymentTerms);
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
