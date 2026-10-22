package com.stoicera.einvoice.validation.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.InvoiceValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The proposer against the golden corpus: each repairable {@code invalid/} file has its expected
 * corrected document and diff in {@code corpus/repaired/}, and the re-validation of the corrected
 * document must come back without a single error.
 */
class RepairProposerTest {

  private static final InvoiceValidator VALIDATOR = new InvoiceValidator();
  private final RepairProposer proposer = new RepairProposer(VALIDATOR);

  @Test
  void peppolEndpointIdsAreDerivedFromTheVatNumbersAndTheCorrectionValidates() {
    RepairProposal proposal =
        propose("invalid/peppol-missing-endpoint-ids.xml", RepairInput.none());

    assertThat(proposal.changes())
        .extracting(
            ProposedChange::ruleId,
            ProposedChange::businessTerm,
            ProposedChange::value,
            ProposedChange::schemeId,
            ProposedChange::source)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "PEPPOL-EN16931-R020", "BT-34", "ATU12345678", "9914", ValueSource.DOCUMENT),
            org.assertj.core.groups.Tuple.tuple(
                "PEPPOL-EN16931-R010", "BT-49", "ATU87654321", "9914", ValueSource.DOCUMENT));
    assertThat(proposal.changes().getFirst().sourceDe()).contains("BT-31", "CompanyID");
    assertThat(proposal.changes().get(1).sourceDe()).contains("BT-48");
    assertThat(proposal.changes()).allSatisfy(c -> assertThat(c.noteDe()).contains("Peppol"));

    assertThat(proposal.correctedXml()).isEqualTo(text("repaired/peppol-missing-endpoint-ids.xml"));
    assertThat(proposal.diff()).isEqualTo(text("repaired/peppol-missing-endpoint-ids.diff"));
    assertThat(proposal.revalidatedValid()).isTrue();
    assertThat(proposal.changes()).allSatisfy(c -> assertThat(proposal.resolves(c)).isTrue());
  }

  @Test
  void orderReferenceWithoutInputIsAPlaceholderAndTheDocumentIsLeftAlone() {
    RepairProposal proposal =
        propose("invalid/at-b2g-01-missing-order-reference.xml", RepairInput.none());

    assertThat(proposal.changes()).hasSize(1);
    ProposedChange change = proposal.changes().getFirst();
    assertThat(change.ruleId()).isEqualTo("AT-B2G-01");
    assertThat(change.businessTerm()).isEqualTo("BT-13");
    assertThat(change.source()).isEqualTo(ValueSource.PLACEHOLDER);
    assertThat(change.value()).isNull();
    assertThat(change.applied()).isFalse();
    assertThat(change.inputName()).isEqualTo(RepairProposer.INPUT_ORDER_REFERENCE);
    assertThat(proposal.pendingInputs()).containsExactly(change);
    // Nothing applied: no corrected document that could pass by accident.
    assertThat(proposal.hasCorrectedDocument()).isFalse();
    assertThat(proposal.diff()).isNull();
    assertThat(proposal.revalidation()).isNull();
    assertThat(proposal.resolves(change)).isFalse();
  }

  @Test
  void orderReferenceFromUserInputIsInsertedInSchemaOrderAndValidates() {
    RepairProposal proposal =
        propose(
            "invalid/at-b2g-01-missing-order-reference.xml", new RepairInput(" BBG-2026-4711 "));

    ProposedChange change = proposal.changes().getFirst();
    assertThat(change.source()).isEqualTo(ValueSource.USER_INPUT);
    assertThat(change.value()).isEqualTo("BBG-2026-4711");
    assertThat(proposal.diff()).isEqualTo(text("repaired/at-b2g-01-missing-order-reference.diff"));
    assertThat(proposal.correctedXml())
        .isEqualTo(text("repaired/at-b2g-01-missing-order-reference.xml"))
        // The corpus file was made by deleting the OrderReference from b2g-full.xml; putting the
        // same value back must give back that file byte for byte.
        .isEqualTo(text("valid/b2g-full.xml"));
    assertThat(proposal.revalidatedValid()).isTrue();
    assertThat(proposal.resolves(change)).isTrue();
  }

  @Test
  void aBlankOrderIdIsFilledInPlace() {
    RepairProposal proposal =
        propose("invalid/at-b2g-01-whitespace-order-id.xml", new RepairInput("BBG-2026-4711"));

    assertThat(proposal.diff()).isEqualTo(text("repaired/at-b2g-01-whitespace-order-id.diff"));
    assertThat(proposal.correctedXml())
        .isEqualTo(text("repaired/at-b2g-01-whitespace-order-id.xml"));
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void anEmptyOrderIdElementIsExpandedNotLeftEmpty() {
    String xml =
        text("invalid/at-b2g-01-whitespace-order-id.xml")
            .replace("<OrderID>   </OrderID>", "<OrderID/>");
    RepairProposal proposal = proposeText(xml, new RepairInput("A-1")).orElseThrow();

    assertThat(proposal.correctedXml())
        .contains("<OrderID>A-1</OrderID>")
        .doesNotContain("<OrderID/>");
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void userInputIsEscapedNotInterpreted() {
    RepairProposal proposal =
        propose(
            "invalid/at-b2g-01-missing-order-reference.xml", new RepairInput("A&B <x> \"q\" 'r'"));

    assertThat(proposal.correctedXml())
        .contains("<OrderID>A&amp;B &lt;x&gt; &quot;q&quot; &apos;r&apos;</OrderID>");
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void aGlnIsUsedWhenTheVatNumberIsNotAustrianOrGerman() {
    String xml =
        text("invalid/peppol-missing-endpoint-ids.xml")
            .replace(
                "<cbc:CompanyID>ATU12345678</cbc:CompanyID>",
                "<cbc:CompanyID>IT12345678901</cbc:CompanyID>")
            .replace(
                "<cbc:ID>LF-4711</cbc:ID>", "<cbc:ID schemeID=\"0088\">9012345000004</cbc:ID>");
    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    ProposedChange seller = proposal.changes().getFirst();
    assertThat(seller.value()).isEqualTo("9012345000004");
    assertThat(seller.schemeId()).isEqualTo("0088");
    assertThat(seller.sourceDe()).contains("BT-29", "GLN");
    assertThat(proposal.correctedXml())
        .contains("<cbc:EndpointID schemeID=\"0088\">9012345000004</cbc:EndpointID>");
    assertThat(proposal.resolves(seller)).isTrue();
  }

  @Test
  void aGermanVatNumberMapsToScheme9930() {
    String xml =
        text("invalid/peppol-missing-endpoint-ids.xml")
            .replace(
                "<cbc:CompanyID>ATU87654321</cbc:CompanyID>",
                "<cbc:CompanyID>DE123456789</cbc:CompanyID>");
    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    ProposedChange buyer = proposal.changes().get(1);
    assertThat(buyer.schemeId()).isEqualTo("9930");
    assertThat(buyer.value()).isEqualTo("DE123456789");
  }

  @Test
  void withoutAnyUsableIdentifierTheEndpointIsAPlaceholderAndNothingIsGuessed() {
    String xml =
        text("invalid/peppol-missing-endpoint-ids.xml")
            .replace(
                "<cbc:CompanyID>ATU87654321</cbc:CompanyID>",
                "<cbc:CompanyID>FR12345678901</cbc:CompanyID>");
    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    ProposedChange buyer = proposal.changes().get(1);
    assertThat(buyer.source()).isEqualTo(ValueSource.PLACEHOLDER);
    assertThat(buyer.value()).isNull();
    assertThat(proposal.correctedXml()).doesNotContain("FR12345678901</cbc:EndpointID>");
    // The seller's address was still derivable, so the correction exists — and the re-validation
    // honestly still reports the buyer's.
    assertThat(proposal.revalidatedValid()).isFalse();
    assertThat(proposal.remainingErrors())
        .extracting(Finding::ruleId)
        .containsExactly("PEPPOL-EN16931-R010");
    assertThat(proposal.resolves(proposal.changes().getFirst())).isTrue();
    assertThat(proposal.resolves(buyer)).isFalse();
  }

  @Test
  void crLfLineEndsAreKept() {
    String xml = text("invalid/peppol-missing-endpoint-ids.xml").replace("\n", "\r\n");
    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    assertThat(proposal.correctedXml())
        .isEqualTo(text("repaired/peppol-missing-endpoint-ids.xml").replace("\n", "\r\n"));
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void aDocumentOnOneLineGetsItsInsertionInline() {
    String xml = text("invalid/peppol-missing-endpoint-ids.xml").replaceAll(">\\s+<", "><");
    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    assertThat(proposal.correctedXml())
        .contains("<cac:Party><cbc:EndpointID schemeID=\"9914\">ATU12345678</cbc:EndpointID>")
        .doesNotContain("\n");
    assertThat(proposal.diff()).contains("+<?xml");
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void aPrefixNotInScopeIsDeclaredOnTheInsertedElement() {
    String xml =
        text("invalid/peppol-missing-endpoint-ids.xml")
            .replace(
                " xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\"",
                "")
            .replaceAll(
                "<cbc:(\\w+)",
                "<cbc:$1 xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\"");
    assertThat(VALIDATOR.validate(xml.getBytes(StandardCharsets.UTF_8)).findings())
        .extracting(Finding::ruleId)
        .containsExactlyInAnyOrder("PEPPOL-EN16931-R020", "PEPPOL-EN16931-R010");

    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    assertThat(proposal.correctedXml())
        .contains(
            "<cbc:EndpointID xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\" schemeID=\"9914\">ATU12345678</cbc:EndpointID>");
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void aTabIndentedDocumentGetsTabIndentedLines() {
    String xml = text("invalid/at-b2g-01-missing-order-reference.xml").replace("  ", "\t");
    RepairProposal proposal = proposeText(xml, new RepairInput("BBG-2026-4711")).orElseThrow();

    assertThat(proposal.correctedXml())
        .contains(
            "\t\t<VATIdentificationNumber>ATU87654321</VATIdentificationNumber>\n"
                + "\t\t<OrderReference>\n"
                + "\t\t\t<OrderID>BBG-2026-4711</OrderID>\n"
                + "\t\t</OrderReference>\n");
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void onlyAVatTaxSchemeCountsAsAVatNumber() {
    // The same ATU number under a tax scheme other than VAT is not BT-31; nothing is derived.
    String xml =
        text("invalid/peppol-missing-endpoint-ids.xml")
            .replaceFirst("<cbc:ID>VAT</cbc:ID>", "<cbc:ID>LOC</cbc:ID>");
    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    ProposedChange seller =
        proposal.changes().stream()
            .filter(c -> c.ruleId().equals("PEPPOL-EN16931-R020"))
            .findFirst()
            .orElseThrow();
    assertThat(seller.source()).isEqualTo(ValueSource.PLACEHOLDER);
  }

  @Test
  void aCharacterTheDocumentsCharsetCannotCarryIsWrittenAsACharacterReference() {
    String xml =
        text("invalid/at-b2g-01-missing-order-reference.xml")
            .replace("encoding=\"UTF-8\"", "encoding=\"ISO-8859-1\"");
    byte[] latin1 = xml.getBytes(StandardCharsets.ISO_8859_1);
    RepairProposal proposal =
        proposer
            .propose(latin1, VALIDATOR.validate(latin1), new RepairInput("BBG-€-4711"))
            .orElseThrow();

    // The euro sign does not exist in ISO-8859-1; written raw it would become "?" on encoding,
    // and the validator would judge a different document than the one shown.
    assertThat(proposal.correctedXml()).contains("<OrderID>BBG-&#x20AC;-4711</OrderID>");
    assertThat(proposal.revalidatedValid()).isTrue();
  }

  @Test
  void aSelfClosingPartyIsNeverWrittenAround() {
    String xml =
        text("invalid/peppol-missing-endpoint-ids.xml")
            .replaceAll(
                "(?s)<cac:AccountingCustomerParty>.*</cac:AccountingCustomerParty>",
                "<cac:AccountingCustomerParty><cac:Party/></cac:AccountingCustomerParty>");
    RepairProposal proposal = proposeText(xml, RepairInput.none()).orElseThrow();

    // Nothing inside <cac:Party/> to derive from, and nothing written next to it.
    assertThat(proposal.changes().get(1).source()).isEqualTo(ValueSource.PLACEHOLDER);
    assertThat(proposal.correctedXml())
        .contains("<cac:AccountingCustomerParty><cac:Party/></cac:AccountingCustomerParty>");
  }

  @Test
  void aValidDocumentGetsNoProposal() {
    assertThat(proposeText(text("valid/b2g-full.xml"), RepairInput.none())).isEmpty();
    assertThat(proposeText(text("valid/peppol-ubl-invoice.xml"), RepairInput.none())).isEmpty();
  }

  @Test
  void findingsWithoutAKnownFixGetNoProposal() {
    assertThat(proposeText(text("invalid/at-b2g-02-invalid-iban.xml"), RepairInput.none()))
        .isEmpty();
    assertThat(proposeText(text("invalid/malformed.xml"), RepairInput.none())).isEmpty();
  }

  @Test
  void aReportThatDoesNotMatchTheBytesProposesNothingForTheOtherFormat() {
    byte[] ubl = bytes("invalid/peppol-missing-endpoint-ids.xml");
    ValidationReport ebiReport =
        VALIDATOR.validate(bytes("invalid/at-b2g-01-missing-order-reference.xml"));

    assertThat(proposer.propose(ubl, ebiReport, new RepairInput("X"))).isEmpty();
  }

  @Test
  void repairInputIsStrippedAndBounded() {
    assertThat(new RepairInput("   ").orderReference()).isNull();
    assertThat(new RepairInput(null).orderReference()).isNull();
    assertThat(new RepairInput("  A-1 ").orderReference()).isEqualTo("A-1");
    assertThat(new RepairInput("x".repeat(255)).orderReference()).hasSize(255);
    // 255 characters as XML counts them, even where Java needs two chars for one.
    assertThat(new RepairInput("😀".repeat(255)).orderReference()).hasSize(510);
    assertThatThrownBy(() -> new RepairInput("x".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("255");
    assertThatThrownBy(() -> new RepairInput("A\u0007B"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RepairInput("A\nB")).isInstanceOf(IllegalArgumentException.class);
  }

  private RepairProposal propose(String resource, RepairInput input) {
    return proposeText(text(resource), input).orElseThrow();
  }

  private Optional<RepairProposal> proposeText(String xml, RepairInput input) {
    byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
    return proposer.propose(bytes, VALIDATOR.validate(bytes), input);
  }

  static String text(String resource) {
    return new String(bytes(resource), StandardCharsets.UTF_8);
  }

  static byte[] bytes(String resource) {
    String path = "corpus/" + resource;
    try (InputStream in = RepairProposerTest.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing corpus resource: " + path);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
