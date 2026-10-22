package com.stoicera.einvoice.validation.repair;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.InvoiceValidator;
import com.stoicera.einvoice.validation.RuleIds;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Proposes a correction for the findings that have a known, mechanical fix, and proves it by
 * validating the corrected document again.
 *
 * <h2>What it fixes, and from where</h2>
 *
 * <ul>
 *   <li>{@code PEPPOL-EN16931-R020} / {@code R010} — the seller's (BT-34) or buyer's (BT-49)
 *       electronic address is missing. Derived from the same party's VAT number (BT-31 / BT-48)
 *       when it is Austrian ({@code ATU} + 8 digits → EAS {@code 9914}) or German ({@code DE} + 9
 *       digits → EAS {@code 9930}); otherwise from a GLN the party already carries as identifier
 *       (BT-29 / BT-46, {@code schemeID="0088"}). VAT first: it is the identifier Austrian Peppol
 *       registrations are most often made under, and a GLN in the document says nothing about
 *       routing. Nothing else is guessed — no other country's VAT scheme, no lookup.
 *   <li>{@code AT-B2G-01} — the Auftragsreferenz (BT-13) is missing or blank. It cannot come from
 *       the document: only the ordering federal body knows it. Supplied as {@link RepairInput}, it
 *       is written; not supplied, the change is listed as a placeholder and the document is left
 *       alone.
 * </ul>
 *
 * <h2>The rule set decides</h2>
 *
 * <p>The corrected bytes go through the same {@link InvoiceValidator} as the upload, and that
 * report is part of the proposal. The proposer never claims success on its own; the caller shows
 * what the validator said. An endpoint id derived from a VAT number is valid by the rules and still
 * may not be where the party receives Peppol documents — each such change says so in its note.
 *
 * <h2>Edits on text, not on a tree</h2>
 *
 * <p>Changes are inserted into the uploaded text at exact positions ({@link XmlText}), so the
 * corrected document differs from the upload only where the proposal says it does, and the diff is
 * honest. The original bytes are never stored; this class holds them only for the duration of a
 * call.
 */
public final class RepairProposer {

  static final String NS_UBL_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
  static final String NS_UBL_CREDIT_NOTE =
      "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
  static final String NS_CAC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
  static final String NS_CBC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
  static final String NS_EBI_61 = "http://www.ebinterface.at/schema/6p1/";

  /** Peppol rule: the seller's electronic address (BT-34) must be provided. */
  public static final String PEPPOL_SELLER_ENDPOINT = "PEPPOL-EN16931-R020";

  /** Peppol rule: the buyer's electronic address (BT-49) must be provided. */
  public static final String PEPPOL_BUYER_ENDPOINT = "PEPPOL-EN16931-R010";

  /** Supported rule ids, in the order their changes are listed. */
  public static final List<String> SUPPORTED_RULES =
      List.of(RuleIds.AT_B2G_01, PEPPOL_SELLER_ENDPOINT, PEPPOL_BUYER_ENDPOINT);

  /** Form and API parameter that carries the Auftragsreferenz. */
  public static final String INPUT_ORDER_REFERENCE = "orderReference";

  private static final Pattern AT_VAT = Pattern.compile("ATU\\d{8}");
  private static final Pattern DE_VAT = Pattern.compile("DE\\d{9}");
  private static final Pattern GLN = Pattern.compile("\\d{13}");

  /** UBL {@code PartyType} children that precede {@code cbc:EndpointID} in the schema sequence. */
  private static final Set<String> BEFORE_ENDPOINT_ID =
      Set.of("MarkCareIndicator", "MarkAttentionIndicator", "WebsiteURI", "LogoReferenceID");

  /** ebInterface {@code AbstractPartyType} children that precede {@code OrderReference}. */
  private static final Set<String> BEFORE_ORDER_REFERENCE =
      Set.of("VATIdentificationNumber", "FurtherIdentification");

  private final InvoiceValidator validator;

  /**
   * @param validator the validator that judged the upload; it judges the correction, too
   */
  public RepairProposer(InvoiceValidator validator) {
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  /**
   * Proposes a correction for {@code report}'s fixable errors.
   *
   * @param xml the uploaded bytes {@code report} was produced from
   * @param report the validator's report on {@code xml}
   * @param input values the user supplied; {@link RepairInput#none()} for none
   * @return the proposal, or empty when no error in the report has a known fix or the document
   *     cannot be edited safely (not well-formed, a charset that does not round-trip)
   */
  public Optional<RepairProposal> propose(byte[] xml, ValidationReport report, RepairInput input) {
    Objects.requireNonNull(report, "report");
    Objects.requireNonNull(input, "input");
    Set<String> fixable = new LinkedHashSet<>();
    for (String rule : SUPPORTED_RULES) {
      for (Finding finding : report.findingsOf(Severity.ERROR)) {
        if (finding.ruleId().equals(rule)) {
          fixable.add(rule);
        }
      }
    }
    if (fixable.isEmpty() || xml == null) {
      return Optional.empty();
    }
    Optional<XmlText> parsed = XmlText.of(xml);
    if (parsed.isEmpty()) {
      return Optional.empty();
    }
    XmlText doc = parsed.get();

    List<Planned> planned = new ArrayList<>();
    for (String rule : fixable) {
      Optional<Planned> plan =
          switch (rule) {
            case RuleIds.AT_B2G_01 -> planOrderReference(doc, input);
            case PEPPOL_SELLER_ENDPOINT -> planEndpoint(doc, Party.SELLER);
            case PEPPOL_BUYER_ENDPOINT -> planEndpoint(doc, Party.BUYER);
            default -> Optional.empty();
          };
      plan.ifPresent(planned::add);
    }
    if (planned.isEmpty()) {
      return Optional.empty();
    }

    List<ProposedChange> changes = planned.stream().map(Planned::change).toList();
    List<TextEdit> edits = planned.stream().flatMap(p -> p.edit().stream()).toList();
    if (edits.isEmpty()) {
      return Optional.of(new RepairProposal(changes, null, null, null));
    }
    String corrected = doc.apply(edits);
    ValidationReport revalidation = validator.validate(doc.encode(corrected));
    String diff = UnifiedDiff.render(doc.text(), edits, "original.xml", "korrigiert.xml");
    return Optional.of(new RepairProposal(changes, corrected, diff, revalidation));
  }

  /** A change and, unless it is a placeholder, the edit that makes it. */
  private record Planned(ProposedChange change, Optional<TextEdit> edit) {}

  // --- AT-B2G-01: Auftragsreferenz --------------------------------------------------------------

  private Optional<Planned> planOrderReference(XmlText doc, RepairInput input) {
    Element root = doc.dom().getDocumentElement();
    if (!NS_EBI_61.equals(root.getNamespaceURI())) {
      return Optional.empty();
    }
    Optional<Element> recipient = child(root, NS_EBI_61, "InvoiceRecipient");
    if (recipient.isEmpty()) {
      return Optional.empty();
    }
    String path = "/Invoice/InvoiceRecipient/OrderReference/OrderID";
    String value = input.orderReference();
    if (value == null) {
      return Optional.of(
          new Planned(
              new ProposedChange(
                  RuleIds.AT_B2G_01,
                  "BT-13",
                  "Auftragsreferenz (Bestellnummer)",
                  path,
                  null,
                  null,
                  ValueSource.PLACEHOLDER,
                  "Steht nicht im Dokument und lässt sich daraus nicht ableiten: Die"
                      + " Auftragsreferenz gibt die bestellende Bundesdienststelle bekannt."
                      + " Bitte eintragen — erst dann wird sie eingefügt und erneut geprüft.",
                  null,
                  INPUT_ORDER_REFERENCE),
              Optional.empty()));
    }

    ProposedChange change =
        new ProposedChange(
            RuleIds.AT_B2G_01,
            "BT-13",
            "Auftragsreferenz (Bestellnummer)",
            path,
            value,
            null,
            ValueSource.USER_INPUT,
            "Von Ihnen eingetragen; nicht aus dem Dokument abgeleitet.",
            "Ob die Referenz zur Bestellung passt, prüft das Regelwerk nicht — nur, dass sie"
                + " vorhanden ist.",
            INPUT_ORDER_REFERENCE);

    Optional<Element> existing = child(recipient.get(), NS_EBI_61, "OrderReference");
    if (existing.isPresent()) {
      // OrderReference present, OrderID blank (the XSD requires OrderID, so it exists).
      Optional<Element> orderId = child(existing.get(), NS_EBI_61, "OrderID");
      if (orderId.isEmpty()) {
        return Optional.empty();
      }
      XmlText.Span span = doc.span(orderId.get());
      TextEdit edit =
          span.isEmptyTag()
              ? new TextEdit(
                  span.startTagStart(),
                  span.startTagEnd() - span.startTagStart(),
                  "<" + span.qname() + ">" + doc.escape(value) + "</" + span.qname() + ">")
              : new TextEdit(
                  span.startTagEnd(), span.endTagStart() - span.startTagEnd(), doc.escape(value));
      return Optional.of(new Planned(change, Optional.of(edit)));
    }

    String eb = qualifier(recipient.get(), NS_EBI_61, "eb");
    String declaration = declaration(recipient.get(), NS_EBI_61, "eb");
    List<String> lines =
        List.of(
            "<" + eb + "OrderReference" + declaration + ">",
            "\t<" + eb + "OrderID>" + doc.escape(value) + "</" + eb + "OrderID>",
            "</" + eb + "OrderReference>");
    return insertChild(doc, recipient.get(), BEFORE_ORDER_REFERENCE, lines)
        .map(edit -> new Planned(change, Optional.of(edit)));
  }

  // --- PEPPOL-EN16931-R020 / R010: electronic addresses --------------------------------------

  private enum Party {
    SELLER(
        "AccountingSupplierParty",
        "BT-34",
        "Elektronische Adresse des Verkäufers",
        "BT-31",
        "USt-IdNr. des Verkäufers",
        "BT-29",
        "Kennung des Verkäufers"),
    BUYER(
        "AccountingCustomerParty",
        "BT-49",
        "Elektronische Adresse des Erwerbers",
        "BT-48",
        "USt-IdNr. des Erwerbers",
        "BT-46",
        "Kennung des Erwerbers");

    final String element;
    final String businessTerm;
    final String fieldDe;
    final String vatTerm;
    final String vatDe;
    final String idTerm;
    final String idDe;

    Party(
        String element,
        String businessTerm,
        String fieldDe,
        String vatTerm,
        String vatDe,
        String idTerm,
        String idDe) {
      this.element = element;
      this.businessTerm = businessTerm;
      this.fieldDe = fieldDe;
      this.vatTerm = vatTerm;
      this.vatDe = vatDe;
      this.idTerm = idTerm;
      this.idDe = idDe;
    }

    String ruleId() {
      return this == SELLER ? PEPPOL_SELLER_ENDPOINT : PEPPOL_BUYER_ENDPOINT;
    }
  }

  /** A value found in the document, with its EAS scheme and the provenance sentence. */
  private record Derived(String value, String schemeId, String sourceDe) {}

  private Optional<Planned> planEndpoint(XmlText doc, Party party) {
    Element root = doc.dom().getDocumentElement();
    String rootNs = root.getNamespaceURI();
    if (!NS_UBL_INVOICE.equals(rootNs) && !NS_UBL_CREDIT_NOTE.equals(rootNs)) {
      return Optional.empty();
    }
    Optional<Element> partyElement =
        child(root, NS_CAC, party.element).flatMap(p -> child(p, NS_CAC, "Party"));
    if (partyElement.isEmpty() || child(partyElement.get(), NS_CBC, "EndpointID").isPresent()) {
      return Optional.empty();
    }
    String path = "/" + root.getLocalName() + "/cac:" + party.element + "/cac:Party/cbc:EndpointID";

    Optional<Derived> derived =
        fromVat(partyElement.get(), party).or(() -> fromGln(partyElement.get(), party));
    if (derived.isEmpty()) {
      return Optional.of(
          new Planned(
              new ProposedChange(
                  party.ruleId(),
                  party.businessTerm,
                  party.fieldDe,
                  path,
                  null,
                  null,
                  ValueSource.PLACEHOLDER,
                  "Im Dokument steht weder eine österreichische oder deutsche USt-IdNr. ("
                      + party.vatTerm
                      + ") noch eine GLN ("
                      + party.idTerm
                      + ") dieser Partei, aus der sich die Adresse ableiten ließe. Bitte die"
                      + " Peppol-Teilnehmerkennung der Partei im Fakturierungssystem ergänzen.",
                  null,
                  null),
              Optional.empty()));
    }

    Derived d = derived.get();
    String cbc = qualifier(partyElement.get(), NS_CBC, "cbc");
    String declaration = declaration(partyElement.get(), NS_CBC, "cbc");
    String line =
        "<"
            + cbc
            + "EndpointID"
            + declaration
            + " schemeID=\""
            + doc.escape(d.schemeId())
            + "\">"
            + doc.escape(d.value())
            + "</"
            + cbc
            + "EndpointID>";
    Optional<TextEdit> edit =
        insertChild(doc, partyElement.get(), BEFORE_ENDPOINT_ID, List.of(line));
    ProposedChange change =
        new ProposedChange(
            party.ruleId(),
            party.businessTerm,
            party.fieldDe,
            path,
            d.value(),
            d.schemeId(),
            ValueSource.DOCUMENT,
            d.sourceDe(),
            "Das Regelwerk prüft Form und Schema der Adresse, nicht ob die Partei darunter im"
                + " Peppol-Netz erreichbar ist. Vor dem Versand im Peppol-Verzeichnis nachsehen.",
            null);
    return edit.map(e -> new Planned(change, Optional.of(e)));
  }

  private Optional<Derived> fromVat(Element party, Party which) {
    for (Element taxScheme : children(party, NS_CAC, "PartyTaxScheme")) {
      boolean isVat =
          child(taxScheme, NS_CAC, "TaxScheme")
              .flatMap(s -> child(s, NS_CBC, "ID"))
              .map(id -> "VAT".equals(id.getTextContent().strip()))
              .orElse(false);
      Optional<String> companyId =
          child(taxScheme, NS_CBC, "CompanyID").map(e -> e.getTextContent().strip());
      if (!isVat || companyId.isEmpty()) {
        continue;
      }
      String vat = companyId.get();
      String where =
          "Aus dem Dokument übernommen: "
              + which.vatDe
              + " ("
              + which.vatTerm
              + ", cac:PartyTaxScheme/cbc:CompanyID). ";
      if (AT_VAT.matcher(vat).matches()) {
        return Optional.of(
            new Derived(
                vat,
                "9914",
                where + "Schema 9914 = österreichische UID-Nummer (Peppol-EAS-Codeliste)."));
      }
      if (DE_VAT.matcher(vat).matches()) {
        return Optional.of(
            new Derived(
                vat, "9930", where + "Schema 9930 = deutsche USt-IdNr. (Peppol-EAS-Codeliste)."));
      }
    }
    return Optional.empty();
  }

  private Optional<Derived> fromGln(Element party, Party which) {
    for (Element identification : children(party, NS_CAC, "PartyIdentification")) {
      Optional<Element> id = child(identification, NS_CBC, "ID");
      if (id.isPresent()
          && "0088".equals(id.get().getAttribute("schemeID").strip())
          && GLN.matcher(id.get().getTextContent().strip()).matches()) {
        return Optional.of(
            new Derived(
                id.get().getTextContent().strip(),
                "0088",
                "Aus dem Dokument übernommen: "
                    + which.idDe
                    + " ("
                    + which.idTerm
                    + ", cac:PartyIdentification/cbc:ID, schemeID 0088). Schema 0088 = GLN"
                    + " (Peppol-EAS-Codeliste)."));
      }
    }
    return Optional.empty();
  }

  // --- Text placement ---------------------------------------------------------------------------

  /**
   * Inserts {@code lines} as a child of {@code parent}: after the last child named in {@code
   * precedingNames} (schema order), or as the first child when there is none. Indentation is copied
   * from the document; {@code \t} in a line stands for one more indentation step. Empty for a
   * self-closing parent ({@code <cac:Party/>}): such a document already fails the schema for other
   * reasons, and rewriting the parent is not a change this class proposes.
   */
  private static Optional<TextEdit> insertChild(
      XmlText doc, Element parent, Set<String> precedingNames, List<String> lines) {
    List<Element> kids = children(parent);
    Element anchor = null;
    for (Element kid : kids) {
      if (precedingNames.contains(kid.getLocalName())) {
        anchor = kid;
      }
    }
    XmlText.Span parentSpan = doc.span(parent);
    if (parentSpan.isEmptyTag()) {
      return Optional.empty();
    }
    Optional<String> parentIndent = doc.indentBefore(parentSpan.startTagStart());
    Optional<String> childIndent =
        kids.isEmpty()
            ? Optional.empty()
            : doc.indentBefore(doc.span(kids.getFirst()).startTagStart());
    int offset = anchor == null ? parentSpan.startTagEnd() : doc.span(anchor).endTagEnd();

    if (childIndent.isEmpty()) {
      // A document on one line: insert inline, no line breaks of our own.
      return Optional.of(TextEdit.insert(offset, String.join("", lines).replace("\t", "")));
    }
    String indent = childIndent.get();
    String step =
        parentIndent
            .filter(p -> indent.startsWith(p) && indent.length() > p.length())
            .map(p -> indent.substring(p.length()))
            .orElse("  ");
    String lineBreak = doc.lineBreak();
    StringBuilder out = new StringBuilder();
    for (String line : lines) {
      String content = line.startsWith("\t") ? step + line.substring(1) : line;
      out.append(lineBreak).append(indent).append(content);
    }
    return Optional.of(TextEdit.insert(offset, out.toString()));
  }

  /** {@code "prefix:"}, or {@code ""} when {@code ns} is the default namespace at {@code at}. */
  private static String qualifier(Element at, String ns, String fallbackPrefix) {
    if (at.isDefaultNamespace(ns)) {
      return "";
    }
    String prefix = at.lookupPrefix(ns);
    return (prefix != null ? prefix : fallbackPrefix) + ":";
  }

  /**
   * A namespace declaration to add when {@code ns} is not in scope at {@code at}, else {@code ""}.
   */
  private static String declaration(Element at, String ns, String fallbackPrefix) {
    if (at.isDefaultNamespace(ns) || at.lookupPrefix(ns) != null) {
      return "";
    }
    return " xmlns:" + fallbackPrefix + "=\"" + ns + "\"";
  }

  private static Optional<Element> child(Element parent, String ns, String localName) {
    return children(parent, ns, localName).stream().findFirst();
  }

  private static List<Element> children(Element parent, String ns, String localName) {
    return children(parent).stream()
        .filter(e -> ns.equals(e.getNamespaceURI()) && localName.equals(e.getLocalName()))
        .toList();
  }

  private static List<Element> children(Element parent) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        out.add(e);
      }
    }
    return out;
  }
}
