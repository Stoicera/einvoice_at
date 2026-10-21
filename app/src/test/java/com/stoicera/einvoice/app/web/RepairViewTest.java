package com.stoicera.einvoice.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.validation.repair.ProposedChange;
import com.stoicera.einvoice.validation.repair.RepairInput;
import com.stoicera.einvoice.validation.repair.RepairProposal;
import com.stoicera.einvoice.validation.repair.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepairViewTest {

  private static final byte[] UPLOAD = "<Invoice/>".getBytes(StandardCharsets.UTF_8);

  private static ProposedChange endpoint(String ruleId, String value) {
    return new ProposedChange(
        ruleId,
        "BT-34",
        "Elektronische Adresse des Verkäufers",
        "/Invoice/cac:AccountingSupplierParty/cac:Party/cbc:EndpointID",
        value,
        value == null ? null : "9914",
        value == null ? ValueSource.PLACEHOLDER : ValueSource.DOCUMENT,
        "Aus dem Dokument übernommen.",
        null,
        null);
  }

  @Test
  void eachChangeIsLabelledByWhatTheRevalidationSaid() {
    ValidationReport stillBroken =
        new ValidationReport(
            "ubl-invoice-2.1",
            "peppol-bis-billing-3.0",
            List.of(Finding.of(Severity.ERROR, "PEPPOL-EN16931-R010", null, "de", "en")));
    RepairProposal proposal =
        new RepairProposal(
            List.of(
                endpoint("PEPPOL-EN16931-R020", "ATU12345678"),
                endpoint("PEPPOL-EN16931-R010", "ATU87654321"),
                endpoint("PEPPOL-EN16931-R010", null)),
            "<Invoice/>",
            "--- a/original.xml\n+++ b/korrigiert.xml\n@@ -1,1 +1,2 @@\n x\n+y\n-z\n",
            stillBroken);

    RepairView view = RepairView.of(proposal, UPLOAD, RepairInput.none());

    assertThat(view.changes())
        .extracting(RepairView.ChangeView::statusClass, RepairView.ChangeView::statusLabel)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("ok", "behoben"),
            org.assertj.core.groups.Tuple.tuple("error", "nicht behoben"),
            org.assertj.core.groups.Tuple.tuple("warn", "Eingabe erforderlich"));
    assertThat(view.changes().getFirst().displayValue()).isEqualTo("ATU12345678 (Schema 9914)");
    assertThat(view.changes().get(2).displayValue()).isEqualTo("—");
    assertThat(view.diffLines())
        .extracting(RepairView.DiffLine::kind)
        .containsExactly("meta", "meta", "hunk", "ctx", "add", "del");
    assertThat(view.revalidatedValid()).isFalse();
    // No change takes input: the document is not sent back to the browser.
    assertThat(view.document()).isNull();
  }

  @Test
  void aChangeThatTakesInputCarriesTheExactUploadBack() {
    ProposedChange orderReference =
        new ProposedChange(
            "AT-B2G-01",
            "BT-13",
            "Auftragsreferenz (Bestellnummer)",
            "/Invoice/InvoiceRecipient/OrderReference/OrderID",
            null,
            null,
            ValueSource.PLACEHOLDER,
            "Steht nicht im Dokument.",
            null,
            "orderReference");
    RepairProposal proposal = new RepairProposal(List.of(orderReference), null, null, null);

    RepairView view = RepairView.of(proposal, UPLOAD, new RepairInput("A-1"));

    assertThat(Base64.getDecoder().decode(view.document())).isEqualTo(UPLOAD);
    assertThat(view.orderReference()).isEqualTo("A-1");
    assertThat(view.diffLines()).isEmpty();
  }
}
