package com.stoicera.einvoice.validation.stage;

import com.helger.ebinterface.v61.Ebi61AccountType;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.ebinterface.v61.Ebi61PaymentMethodType;
import com.helger.ebinterface.v61.Ebi61UniversalBankTransactionType;
import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.RuleIds;
import com.stoicera.einvoice.validation.ValidationContext;
import com.stoicera.einvoice.validation.ValidationStage;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written Austrian B2G business rules that operate on the parsed ebInterface 6.1 tree rather
 * than the DOM — the rules that are cleaner expressed in Java than in Schematron/XPath.
 *
 * <p>Currently one rule, {@code AT-B2G-02}: every {@code IBAN} present under {@code
 * PaymentMethod/UniversalBankTransaction/BeneficiaryAccount} must pass the core {@link Iban} mod-97
 * checksum. The XSD only bounds an IBAN's length, so a structurally valid document can still carry
 * a transposed or mistyped IBAN; this rule catches that. It is deliberately <em>narrow</em>: a
 * missing payment method or a beneficiary account without an {@code IBAN} element is not an error
 * here — structural presence is the XSD's job, and a full B2G payment-completeness rule set is a
 * later milestone (see ADR-0004). The facade runs this stage only on an XSD-valid document, so the
 * parsed tree is always present.
 *
 * <p>The finding never echoes the IBAN: it is bank-account PII (see {@link Iban}'s own discipline).
 * The message and location name the account by its 1-based position, so a reader can locate the bad
 * value without the report leaking it.
 */
public final class BusinessRuleStage implements ValidationStage {

  @Override
  public List<Finding> apply(ValidationContext ctx) {
    // The facade runs this stage only after an XSD-clean parse, so the tree is present.
    Ebi61InvoiceType invoice = ctx.ebiInvoice().orElseThrow();

    List<Finding> findings = new ArrayList<>();

    Ebi61PaymentMethodType paymentMethod = invoice.getPaymentMethod();
    if (paymentMethod == null) {
      return findings; // no payment method is not an AT-B2G-02 concern
    }
    Ebi61UniversalBankTransactionType bankTransaction = paymentMethod.getUniversalBankTransaction();
    if (bankTransaction == null) {
      return findings; // a non-transfer payment method carries no beneficiary IBAN to check
    }

    List<Ebi61AccountType> accounts = bankTransaction.getBeneficiaryAccount();
    for (int index = 0; index < accounts.size(); index++) {
      int position = index + 1; // XPath and the finding message are 1-based
      String iban = accounts.get(index).getIBAN();
      if (iban == null || iban.isBlank()) {
        continue; // a missing IBAN element is the XSD's concern, not this rule's
      }
      if (!isValidIban(iban)) {
        findings.add(checksumFinding(position));
      }
    }
    return findings;
  }

  /**
   * Whether {@code rawIban} is a structurally valid, mod-97-correct IBAN. The core {@link Iban}
   * exposes only a throwing factory (it never keeps an invalid instance around), so a failed
   * construction — signalled by {@link InvariantViolationException} — is how an invalid IBAN is
   * detected. The exception message never contains the IBAN, so nothing PII escapes here either.
   */
  private static boolean isValidIban(String rawIban) {
    try {
      new Iban(rawIban);
      return true;
    } catch (InvariantViolationException invalid) {
      return false;
    }
  }

  private static Finding checksumFinding(int position) {
    return Finding.of(
        Severity.ERROR,
        RuleIds.AT_B2G_02,
        "/Invoice/PaymentMethod/UniversalBankTransaction/BeneficiaryAccount[" + position + "]/IBAN",
        "IBAN im Empfängerkonto " + position + " ist ungültig (Prüfsummenfehler).",
        "IBAN in beneficiary account " + position + " is invalid (checksum failure).");
  }
}
