package com.stoicera.einvoice.validation.stage;

import com.helger.ebinterface.EEbInterfaceVersion;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.formats.ebinterface.EbInterfaceNamespaces;
import com.stoicera.einvoice.formats.ubl.UblDocumentKind;
import com.stoicera.einvoice.formats.ubl.UblNamespaces;
import com.stoicera.einvoice.validation.DocumentFormat;
import com.stoicera.einvoice.validation.RuleIds;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.ValidationStage;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Document;

/**
 * Detects the invoice format from the root element's XML namespace and records it on the context.
 *
 * <p>The stage runs only after a DOM is available. It resolves the root namespace against the known
 * ebInterface versions and the two UBL billing document kinds: an unrecognised namespace is {@code
 * FORMAT-01} (unknown format); a recognised but unsupported ebInterface version is {@code
 * FORMAT-02} (naming the found and the supported version); a supported format produces no finding
 * and sets {@link ValidationContext#format(DocumentFormat)}, so the facade can pick the right
 * pipeline. Success is communicated solely by returning an empty finding list.
 *
 * <p>Note the asymmetry between the two format families, which is real and not an oversight: an
 * ebInterface document declares its <em>version</em> in the namespace, so a 6.0 document is
 * recognised precisely well enough to be rejected with a useful message. UBL declares only the
 * document kind — invoice or credit note — and everything version-like about Peppol BIS lives in
 * {@code cbc:CustomizationID} inside the document, where the OpenPeppol Schematron checks it far
 * better than a namespace comparison could.
 */
public final class FormatDetectionStage implements ValidationStage {

  /** The only ebInterface version this validator supports. */
  private static final EEbInterfaceVersion SUPPORTED_VERSION = EEbInterfaceVersion.V61;

  @Override
  public List<Finding> apply(ValidationContext ctx) {
    Document dom = ctx.dom().orElseThrow(); // the facade runs this stage only with a parsed DOM
    String namespaceUri = dom.getDocumentElement().getNamespaceURI();

    Optional<UblDocumentKind> ublKind = UblNamespaces.documentKindOf(namespaceUri);
    if (ublKind.isPresent()) {
      ctx.format(
          switch (ublKind.get()) {
            case INVOICE -> DocumentFormat.UBL_INVOICE;
            case CREDIT_NOTE -> DocumentFormat.UBL_CREDIT_NOTE;
          });
      return List.of();
    }

    Optional<EEbInterfaceVersion> version = EbInterfaceNamespaces.versionOf(namespaceUri);
    if (version.isEmpty()) {
      return List.of(
          Finding.of(
              Severity.ERROR,
              RuleIds.FORMAT_01,
              null,
              "Unbekanntes Rechnungsformat: Der XML-Namensraum des Wurzelelements entspricht weder"
                  + " einer unterstützten ebInterface-Version noch einem UBL-Rechnungsdokument.",
              "Unknown invoice format: the root element namespace matches neither a supported"
                  + " ebInterface version nor a UBL invoice document."));
    }

    EEbInterfaceVersion detected = version.get();
    if (detected != SUPPORTED_VERSION) {
      String found = detected.getVersion().getAsStringMajorMinor();
      String supported = SUPPORTED_VERSION.getVersion().getAsStringMajorMinor();
      String messageDe =
          "Nicht unterstützte ebInterface-Version %s gefunden; unterstützt wird ausschließlich Version %s."
              .formatted(found, supported);
      String messageEn =
          "Unsupported ebInterface version %s; only version %s is supported."
              .formatted(found, supported);
      return List.of(Finding.of(Severity.ERROR, RuleIds.FORMAT_02, null, messageDe, messageEn));
    }

    ctx.format(DocumentFormat.EBINTERFACE_61);
    return List.of();
  }
}
