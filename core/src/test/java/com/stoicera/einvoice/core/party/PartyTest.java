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
  void malformedVatIdMessageSanitizesControlCharactersAndIsBounded() {
    String withControlChar = "AT\n2345678901234567890";
    assertThatThrownBy(() -> new Party("X GmbH", LINZ, withControlChar))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageNotContaining("\n")
        .hasMessageContaining("?")
        .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(150));
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
  }

  @Test
  void countryCodeIsNormalizedToTrimmedUppercase() {
    assertThat(new Address("Hauptplatz 1", "Linz", "4020", " at ").countryCode()).isEqualTo("AT");
    assertThat(new Address("Hauptplatz 1", "Linz", "4020", "at").countryCode()).isEqualTo("AT");
  }

  @Test
  void partyNameLengthIsCappedAtTwoFiftySixCharacters() {
    String atLimit = "x".repeat(256);
    assertThat(new Party(atLimit, LINZ, null).name()).hasSize(256);
    String overLimit = "x".repeat(257);
    assertThatThrownBy(() -> new Party(overLimit, LINZ, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("name");
  }

  @Test
  void addressStreetAndCityLengthAreCappedAtTwoFiftySixCharacters() {
    String atLimit = "x".repeat(256);
    Address accepted = new Address(atLimit, atLimit, "4020", "AT");
    assertThat(accepted.street()).hasSize(256);
    assertThat(accepted.city()).hasSize(256);
    String overLimit = "x".repeat(257);
    assertThatThrownBy(() -> new Address(overLimit, "Linz", "4020", "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("street");
    assertThatThrownBy(() -> new Address("Hauptplatz 1", overLimit, "4020", "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("city");
  }

  @Test
  void addressPostalCodeLengthIsCappedAtSixteenCharacters() {
    String atLimit = "1".repeat(16);
    assertThat(new Address("Hauptplatz 1", "Linz", atLimit, "AT").postalCode()).hasSize(16);
    String overLimit = "1".repeat(17);
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", overLimit, "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("postal");
  }

  @Test
  void countryCodeRejectionMessageSanitizesAndBoundsTheEcho() {
    String withControlChar = "A\n" + "x".repeat(80);
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", "4020", withControlChar))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("country")
        .hasMessageNotContaining("\n")
        .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(150));
  }
}
