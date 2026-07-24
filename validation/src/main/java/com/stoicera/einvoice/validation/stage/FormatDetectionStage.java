package com.stoicera.einvoice.validation.stage;

import com.helger.ebinterface.EEbInterfaceVersion;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.formats.ebinterface.EbInterfaceNamespaces;
import com.stoicera.einvoice.validation.RuleIds;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.ValidationStage;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Document;

/**
 * Detects the invoice format from the root element's XML namespace.
 *
 * <p>The stage runs only after a DOM is available. It resolves the root namespace against the known
 * ebInterface versions: an unrecognised namespace is {@code FORMAT-01} (unknown format); a
 * recognised but unsupported ebInterface version is {@code FORMAT-02} (naming the found and the
 * supported version); the supported version 6.1 produces no finding, so the pipeline continues to
 * the XSD stage. Success is communicated solely by returning an empty finding list.
 */
public final class FormatDetectionStage implements ValidationStage {

  /** The only ebInterface version this validator supports. */
  private static final EEbInterfaceVersion SUPPORTED_VERSION = EEbInterfaceVersion.V61;

  @Override
  public List<Finding> apply(ValidationContext ctx) {
    Document dom = ctx.dom().orElseThrow(); // the facade runs this stage only with a parsed DOM
    String namespaceUri = dom.getDocumentElement().getNamespaceURI();

    Optional<EEbInterfaceVersion> version = EbInterfaceNamespaces.versionOf(namespaceUri);
    if (version.isEmpty()) {
      return List.of(
          Finding.of(
              Severity.ERROR,
              RuleIds.FORMAT_01,
              null,
              "Unbekanntes Rechnungsformat: Der XML-Namensraum des Wurzelelements entspricht keiner"
                  + " unterstützten ebInterface-Version.",
              "Unknown invoice format: the root element namespace matches no supported ebInterface"
                  + " version."));
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

    return List.of();
  }
}
