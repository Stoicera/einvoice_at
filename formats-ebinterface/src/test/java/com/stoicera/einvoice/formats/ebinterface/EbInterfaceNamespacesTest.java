package com.stoicera.einvoice.formats.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.ebinterface.EEbInterfaceVersion;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EbInterfaceNamespacesTest {

  @Test
  void versionOfResolvesTheEbInterface61Namespace() {
    Optional<EEbInterfaceVersion> version =
        EbInterfaceNamespaces.versionOf("http://www.ebinterface.at/schema/6p1/");

    assertThat(version).contains(EEbInterfaceVersion.V61);
  }

  @Test
  void versionOfReturnsEmptyForAnUnknownNamespace() {
    assertThat(EbInterfaceNamespaces.versionOf("urn:example:nope")).isEmpty();
  }
}
