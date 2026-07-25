package com.stoicera.einvoice.core.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.util.Optional;
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
  void threeArgConstructorLeavesEmailAbsent() {
    assertThat(new Party("Kleinunternehmer GmbH", LINZ, null).email()).isEmpty();
  }

  @Test
  void shorterConstructorsLeaveTheElectronicAddressAbsent() {
    assertThat(new Party("Kleinunternehmer GmbH", LINZ, null).electronicAddress()).isEmpty();
    assertThat(new Party("Kleinunternehmer GmbH", LINZ, null, Optional.empty()).electronicAddress())
        .isEmpty();
  }

  /**
   * BT-34/BT-49 is a network routing address, not a contact address: a party may carry an e-mail,
   * an electronic address, both, or neither, and the two never stand in for one another.
   */
  @Test
  void electronicAddressIsIndependentOfEmail() {
    Party p =
        new Party(
            "Stoicera Software Group",
            LINZ,
            "ATU12345678",
            Optional.empty(),
            Optional.of(new ElectronicAddress("9915", "ATU12345678")));

    assertThat(p.email()).isEmpty();
    assertThat(p.electronicAddress()).contains(new ElectronicAddress("9915", "ATU12345678"));
  }

  @Test
  void nullElectronicAddressIsRejectedInFavourOfAnEmptyOptional() {
    assertThatThrownBy(() -> new Party("Stoicera", LINZ, "ATU12345678", Optional.empty(), null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("use Optional.empty() when absent");
  }

  @Test
  void emailIsOptionalAndAbsentWhenNotSupplied() {
    Party p = new Party("Stoicera Software Group", LINZ, "ATU12345678", Optional.empty());
    assertThat(p.email()).isEmpty();
  }

  @Test
  void validEmailIsAcceptedAndTrimmed() {
    Party p =
        new Party(
            "Stoicera Software Group", LINZ, "ATU12345678", Optional.of("  info@stoicera.at  "));
    assertThat(p.email()).contains("info@stoicera.at");
  }

  @Test
  void nullEmailOptionalIsRejected() {
    assertThatThrownBy(() -> new Party("Stoicera Software Group", LINZ, "ATU12345678", null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("email");
  }

  @Test
  void rejectsEmailWithoutAtSign() {
    assertThatThrownBy(
            () ->
                new Party(
                    "Stoicera Software Group",
                    LINZ,
                    "ATU12345678",
                    Optional.of("info-at-stoicera.at")))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("email");
  }

  @Test
  void rejectsEmailWithEmptyLocalPart() {
    assertThatThrownBy(
            () ->
                new Party(
                    "Stoicera Software Group", LINZ, "ATU12345678", Optional.of("@stoicera.at")))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("email");
  }

  @Test
  void rejectsEmailWithEmptyDomainPart() {
    assertThatThrownBy(
            () -> new Party("Stoicera Software Group", LINZ, "ATU12345678", Optional.of("info@")))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("email");
  }

  @Test
  void rejectsEmailContainingWhitespace() {
    assertThatThrownBy(
            () ->
                new Party(
                    "Stoicera Software Group",
                    LINZ,
                    "ATU12345678",
                    Optional.of("info office@stoicera.at")))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("email");
  }

  @Test
  void rejectsBlankEmail() {
    assertThatThrownBy(
            () -> new Party("Stoicera Software Group", LINZ, "ATU12345678", Optional.of("   ")))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("email");
  }

  @Test
  void emailLengthIsCappedAtTwoFiftySixCharacters() {
    String atLimit = "a".repeat(250) + "@ab.at"; // 256 chars total, valid shape
    assertThat(atLimit).hasSize(256);
    Party p = new Party("Stoicera Software Group", LINZ, "ATU12345678", Optional.of(atLimit));
    assertThat(p.email()).contains(atLimit);
    String overLimit = "a".repeat(251) + "@ab.at";
    assertThatThrownBy(
            () -> new Party("Stoicera Software Group", LINZ, "ATU12345678", Optional.of(overLimit)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("email");
  }

  @Test
  void malformedEmailMessageSanitizesControlCharactersAndIsBounded() {
    String withControlChar = "info\n" + "x".repeat(80) + "@stoicera.at";
    assertThatThrownBy(
            () ->
                new Party(
                    "Stoicera Software Group", LINZ, "ATU12345678", Optional.of(withControlChar)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageNotContaining("\n")
        .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(200));
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
        .hasMessageContaining("Country");
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
        .hasMessageContaining("Street");
    assertThatThrownBy(() -> new Address("Hauptplatz 1", overLimit, "4020", "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("City");
  }

  @Test
  void addressPostalCodeLengthIsCappedAtSixteenCharacters() {
    String atLimit = "1".repeat(16);
    assertThat(new Address("Hauptplatz 1", "Linz", atLimit, "AT").postalCode()).hasSize(16);
    String overLimit = "1".repeat(17);
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", overLimit, "AT"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("Postal");
  }

  @Test
  void countryCodeAtSixteenCharactersIsRejectedByTheRegexNotTheLengthGuard() {
    String sixteenCharsInvalid = "x".repeat(16);
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", "4020", sixteenCharsInvalid))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("ISO 3166-1 alpha-2");
  }

  @Test
  void countryCodeOverSixteenCharactersIsRejectedBeforeNormalization() {
    String overLimit = "x".repeat(17);
    assertThatThrownBy(() -> new Address("Hauptplatz 1", "Linz", "4020", overLimit))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Address country code exceeds 16 characters");
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
