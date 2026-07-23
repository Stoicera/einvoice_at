package com.stoicera.einvoice.core.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import org.junit.jupiter.api.Test;

class PartyTest {

  private static final Address LINZ = new Address("Hauptplatz 1", "Linz", "4020", "AT");

  @Test
  void validPartyWithAustrianVatId() {
    Party p = new Party("Stoicera Software Group", LINZ, "ATU12345678");
    assertThat(p.vatId()).isEqualTo("ATU12345678");
  }

  @Test
  void vatIdIsOptional() {
    assertThat(new Party("Kleinunternehmer GmbH", LINZ, null).vatId()).isNull();
  }

  @Test
  void rejectsBlankPartyName() {
    assertThatThrownBy(() -> new Party("  ", LINZ, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> new Party(null, LINZ, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("name");
  }

  @Test
  void rejectsMissingAddress() {
    assertThatThrownBy(() -> new Party("X GmbH", null, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("address");
  }

  @Test
  void rejectsMalformedVatId() {
    assertThatThrownBy(() -> new Party("X GmbH", LINZ, "12345678"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("VAT");
    assertThatThrownBy(() -> new Party("X GmbH", LINZ, "A"))
        .isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void addressFieldValidation() {
    assertThatThrownBy(() -> new Address("", "Linz", "4020", "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("street");
    assertThatThrownBy(() -> new Address("Hauptplatz 1", " ", "4020", "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("city");
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", null, "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("postal");
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", "4020", "Austria"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("country");
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", "4020", "at"))
        .isInstanceOf(InvariantViolationException.class);
  }
}
