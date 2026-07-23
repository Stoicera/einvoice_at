package com.stoicera.einvoice.core.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.money.Money;
import com.stoicera.einvoice.core.tax.VatRate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class InvoiceLineTest {

  private static InvoiceLine line(String qty, String price) {
    return new InvoiceLine(
        "1", "Beratung", new BigDecimal(qty), "HUR", new BigDecimal(price), VatRate.STANDARD_20);
  }

  @Test
  void netAmountRoundsOnceCommercially() {
    assertThat(line("3", "99.99").netAmount(Money.EUR)).isEqualTo(Money.of("299.97", Money.EUR));
    // 1.5 * 0.07 = 0.105 -> 0.11 (HALF_UP, single rounding step)
    assertThat(line("1.5", "0.07").netAmount(Money.EUR)).isEqualTo(Money.of("0.11", Money.EUR));
    // 0.333 * 0.333 = 0.110889 -> 0.11
    assertThat(line("0.333", "0.333").netAmount(Money.EUR)).isEqualTo(Money.of("0.11", Money.EUR));
  }

  @Test
  void negativeQuantityGivesNegativeNet() {
    assertThat(line("-2", "10.00").netAmount(Money.EUR)).isEqualTo(Money.of("-20.00", Money.EUR));
  }

  @Test
  void scaleFourQuantityAndPriceAreAcceptedExactly() {
    InvoiceLine line =
        new InvoiceLine(
            "1",
            "Feinmenge",
            new BigDecimal("0.0001"),
            "KGM",
            new BigDecimal("1234.5678"),
            VatRate.STANDARD_20);
    assertThat(line.quantity()).isEqualByComparingTo("0.0001");
    assertThat(line.netAmount(Money.EUR)).isEqualTo(Money.of("0.12", Money.EUR)); // 0.12345678
  }

  @Test
  void rejectsInvalidComponents() {
    assertThatThrownBy(() -> line("0", "1.00"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("quantity");
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1", "Beratung", null, "HUR", new BigDecimal("1.00"), VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("quantity");
    assertThatThrownBy(() -> line("1", "-0.01"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("price");
    assertThatThrownBy(
            () ->
                new InvoiceLine("1", "Beratung", BigDecimal.ONE, "HUR", null, VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("price");
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    null, "x", BigDecimal.ONE, "C62", BigDecimal.ONE, VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("id");
    assertThatThrownBy(() -> line("1.00001", "1.00"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("scale");
    assertThatThrownBy(() -> line("1", "1.00001"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("scale");
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    " ", "x", BigDecimal.ONE, "C62", BigDecimal.ONE, VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("id");
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1", "", BigDecimal.ONE, "C62", BigDecimal.ONE, VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("description");
    assertThatThrownBy(
            () ->
                new InvoiceLine("1", "x", BigDecimal.ONE, "", BigDecimal.ONE, VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("unit");
    assertThatThrownBy(() -> new InvoiceLine("1", "x", BigDecimal.ONE, "C62", BigDecimal.ONE, null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("VAT");
  }

  @Test
  void scaleViolationMessagesStateScaleNumbersNotTheRawValue() {
    // Never toPlainString() an unvalidated value: state the two scale numbers instead.
    assertThatThrownBy(() -> line("1.00001", "1.00"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Line quantity scale 5 exceeds scale 4");
    assertThatThrownBy(() -> line("1", "1.00001"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Unit price scale 5 exceeds scale 4");
  }

  @Test
  @Timeout(5)
  void astronomicalQuantityAndPriceAreRejected() {
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1",
                    "Bombe",
                    new BigDecimal("1E+100000"),
                    "C62",
                    BigDecimal.ONE,
                    VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1",
                    "Bombe",
                    BigDecimal.ONE,
                    "C62",
                    new BigDecimal("1E+100000"),
                    VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void nullAndZeroQuantityHaveDistinctMessages() {
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1", "Beratung", null, "HUR", new BigDecimal("1.00"), VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Line quantity must not be null");
    assertThatThrownBy(() -> line("0", "1.00"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Line quantity must be non-zero");
  }

  @Test
  void nullAndNegativeUnitPriceHaveDistinctMessages() {
    assertThatThrownBy(
            () ->
                new InvoiceLine("1", "Beratung", BigDecimal.ONE, "HUR", null, VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Unit price must not be null");
    assertThatThrownBy(() -> line("1", "-0.01"))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessage("Unit price must be non-negative (EN 16931 BR-27)");
  }

  @Test
  void idLengthIsCappedAtOneTwentyEightCharacters() {
    String atLimit = "x".repeat(128);
    InvoiceLine accepted =
        new InvoiceLine(
            atLimit, "Beratung", BigDecimal.ONE, "HUR", BigDecimal.ONE, VatRate.STANDARD_20);
    assertThat(accepted.id()).hasSize(128);
    String overLimit = "x".repeat(129);
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    overLimit,
                    "Beratung",
                    BigDecimal.ONE,
                    "HUR",
                    BigDecimal.ONE,
                    VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("id");
  }

  @Test
  void descriptionLengthIsCappedAtFourThousandNinetySixCharacters() {
    String atLimit = "x".repeat(4096);
    InvoiceLine accepted =
        new InvoiceLine("1", atLimit, BigDecimal.ONE, "HUR", BigDecimal.ONE, VatRate.STANDARD_20);
    assertThat(accepted.description()).hasSize(4096);
    String overLimit = "x".repeat(4097);
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1", overLimit, BigDecimal.ONE, "HUR", BigDecimal.ONE, VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("description");
  }

  @Test
  void unitCodeLengthIsCappedAtEightCharacters() {
    String atLimit = "x".repeat(8);
    InvoiceLine accepted =
        new InvoiceLine(
            "1", "Beratung", BigDecimal.ONE, atLimit, BigDecimal.ONE, VatRate.STANDARD_20);
    assertThat(accepted.unitCode()).hasSize(8);
    String overLimit = "x".repeat(9);
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1",
                    "Beratung",
                    BigDecimal.ONE,
                    overLimit,
                    BigDecimal.ONE,
                    VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("unit");
  }

  @Test
  void quantityAndPriceIntegerDigitCeilings() {
    // 7 integer digits max for quantity, 8 for unit price -> product stays within Money's 15
    InvoiceLine max =
        new InvoiceLine(
            "1",
            "Grenze",
            new BigDecimal("9999999"),
            "C62",
            new BigDecimal("99999999"),
            VatRate.STANDARD_20);
    assertThat(max.netAmount(Money.EUR).amount()).isEqualByComparingTo("999999890000001.00");
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1",
                    "X",
                    new BigDecimal("10000000"),
                    "C62",
                    BigDecimal.ONE,
                    VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class);
    assertThatThrownBy(
            () ->
                new InvoiceLine(
                    "1",
                    "X",
                    BigDecimal.ONE,
                    "C62",
                    new BigDecimal("100000000"),
                    VatRate.STANDARD_20))
        .isInstanceOf(InvariantViolationException.class);
  }
}
