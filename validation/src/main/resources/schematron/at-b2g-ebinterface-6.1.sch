<?xml version="1.0" encoding="UTF-8"?>
<!--
  Original AT-B2G business rules of einvoice-at, not derived from any AUSTRIAPRO artefact;
  ebInterface itself ships no official Schematron.

  These rules express the Austrian business-to-government (B2G) profile on top of the structural
  ebInterface 6.1 XSD: constraints the schema cannot state but a federal recipient requires. Each
  assert carries a stable id (AT-B2G-nn); the German/English finding texts are owned by
  SchematronRuleCatalog, keyed by that id. The assert text below is the German finding text so the
  raw SVRL stays useful even if an id is ever removed from the catalog.

  The prefix `eb` binds to EEbInterfaceVersion.V61.getNamespaceURI().
-->
<schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt">
  <title>einvoice-at AT-B2G rules for ebInterface 6.1</title>
  <ns prefix="eb" uri="http://www.ebinterface.at/schema/6p1/"/>
  <pattern id="at-b2g">
    <rule context="/eb:Invoice">
      <assert id="AT-B2G-01" test="normalize-space(eb:InvoiceRecipient/eb:OrderReference/eb:OrderID) != ''"
        >Auftragsreferenz fehlt: Rechnungen an Bundesdienststellen müssen eine Auftragsreferenz (OrderReference/OrderID) enthalten.</assert>
      <assert id="AT-B2G-03" test="normalize-space(eb:Biller/eb:Address/eb:Email) != ''"
        >Für Bundesdienststellen ist eine E-Mail-Adresse des Rechnungsstellers erforderlich (Biller/Address/Email).</assert>
      <assert id="AT-B2G-04" test="normalize-space(eb:Biller/eb:InvoiceRecipientsBillerID) != ''"
        >Für Bundesdienststellen ist die Lieferantennummer erforderlich (Biller/InvoiceRecipientsBillerID).</assert>
      <assert id="AT-B2G-05" test="eb:PaymentMethod/eb:UniversalBankTransaction or eb:PaymentMethod/eb:NoPayment"
        >Eine Zahlungsmethode ist erforderlich (PaymentMethod: UniversalBankTransaction oder NoPayment).</assert>
    </rule>
  </pattern>
</schema>
