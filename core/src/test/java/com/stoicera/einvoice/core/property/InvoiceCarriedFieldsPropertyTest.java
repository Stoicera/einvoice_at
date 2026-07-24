package com.stoicera.einvoice.core.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.invoice.Invoice;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Coverage property for the M3 carried-over fields (BT-72/BG-14 delivery date/service period, BG-14
 * party email): pins that {@link Generators#invoices()} actually exercises every arm — present and
 * absent alike — rather than passing vacuously because an arm is never generated. {@link
 * InvoiceArithmeticPropertyTest}'s javadoc records why this repo treats vacuous properties as a
 * standing concern (finding P1.2); this test is the non-vacuity guard for the new arms.
 */
class InvoiceCarriedFieldsPropertyTest {

  @Provide
  Arbitrary<Invoice> invoices() {
    return Generators.invoices();
  }

  @Property
  void deliveryAndEmailArmsAreMutuallyExclusiveAndAllExercised(
      @ForAll("invoices") Invoice invoice) {
    assertThat(invoice.deliveryDate().isPresent() && invoice.servicePeriod().isPresent())
        .as("deliveryDate and servicePeriod must never both be present (§ 11 Abs 1 Z 4 UStG)")
        .isFalse();

    Statistics.label("delivery arm")
        .collect(deliveryArm(invoice))
        .coverage(
            checker -> {
              checker.check("none").count(c -> c > 0);
              checker.check("deliveryDate").count(c -> c > 0);
              checker.check("servicePeriod").count(c -> c > 0);
            });
    Statistics.label("seller email arm")
        .collect(invoice.seller().email().isPresent() ? "present" : "absent")
        .coverage(
            checker -> {
              checker.check("present").count(c -> c > 0);
              checker.check("absent").count(c -> c > 0);
            });
    Statistics.label("buyer email arm")
        .collect(invoice.buyer().email().isPresent() ? "present" : "absent")
        .coverage(
            checker -> {
              checker.check("present").count(c -> c > 0);
              checker.check("absent").count(c -> c > 0);
            });
  }

  private static String deliveryArm(Invoice invoice) {
    if (invoice.deliveryDate().isPresent()) {
      return "deliveryDate";
    }
    if (invoice.servicePeriod().isPresent()) {
      return "servicePeriod";
    }
    return "none";
  }
}
