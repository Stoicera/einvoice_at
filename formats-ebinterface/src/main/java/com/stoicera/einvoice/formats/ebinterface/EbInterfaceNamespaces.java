package com.stoicera.einvoice.formats.ebinterface;

import com.helger.ebinterface.EEbInterfaceVersion;
import java.util.Optional;

/**
 * Maps XML target namespaces to the ph-ebinterface {@link EEbInterfaceVersion} they identify.
 *
 * <p>This is the seam that lets a caller pick the right {@link EbInterfaceVersionStrategy} from an
 * unknown ebInterface document without hard-coding namespace strings.
 */
public final class EbInterfaceNamespaces {

  private EbInterfaceNamespaces() {}

  /**
   * Resolves the ebInterface version whose namespace equals {@code namespaceUri}.
   *
   * @param namespaceUri the XML target namespace to look up
   * @return the matching version, or {@link Optional#empty()} if none matches
   */
  public static Optional<EEbInterfaceVersion> versionOf(String namespaceUri) {
    return Optional.ofNullable(EEbInterfaceVersion.getFromNamespaceURIOrNull(namespaceUri));
  }
}
