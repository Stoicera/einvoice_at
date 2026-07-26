package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.app.invoice.DuplicateInvoiceException;
import com.stoicera.einvoice.app.invoice.InvoiceCreated;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.security.CurrentTenant;
import com.stoicera.einvoice.core.InvariantViolationException;
import com.stoicera.einvoice.mapping.json.InvoiceJsonException;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The create-invoice wizard: four server-rendered steps (SPEC §5, ADR-0009 Entscheidung 5 — "a
 * server-rendered multi-step wizard", corrected from "htmx wizard" because none of these steps
 * needs a partial update).
 *
 * <pre>
 *   1  /neu, /neu/kopf         Kopfdaten     — number, type, dates, currency, references
 *   2  /neu/parteien           Parteien      — seller and buyer
 *   3  /neu/positionen         Positionen    — add and remove lines
 *   4  /neu/zahlung, /anlegen  Zahlung       — payment details, review, create
 * </pre>
 *
 * <h2>Every step is POST-and-redirect</h2>
 *
 * <p>A step validates, writes into the session draft, and redirects to the next step's GET. So the
 * back button works, a reload never re-submits, and the URL always names the step the user is
 * looking at. Nothing here needs JavaScript.
 *
 * <h2>Validation is split deliberately, not duplicated</h2>
 *
 * <p>Each step checks only <em>presence</em> of the fields it collects — enough to keep the user
 * from reaching step 4 and being told that step 1 was wrong. Everything that is a real rule (IBAN
 * checksum, VAT category consistency, totals, at least one line) belongs to {@code core}, is
 * enforced there, and is caught at {@link #create} and rendered as a message. Re-implementing those
 * checks here would give the platform a second, drifting definition of a valid invoice.
 *
 * <p>The one exception is "at least one line", checked when leaving step 3: {@code core} does
 * enforce it, but discovering it two steps later would point the user at the wrong page.
 */
@Controller
@RequestMapping("/app/rechnungen/neu")
public class InvoiceWizardController {

  /** Session key for the draft. One draft per session — see {@code InvoiceWizardIT}. */
  private static final String DRAFT = "invoiceDraft";

  private final InvoiceService invoices;
  private final CurrentTenant currentTenant;

  public InvoiceWizardController(InvoiceService invoices, CurrentTenant currentTenant) {
    this.invoices = invoices;
    this.currentTenant = currentTenant;
  }

  // ------------------------------------------------------------ 1. Kopfdaten

  @GetMapping
  public String headerStep(Authentication authentication, HttpSession session, Model model) {
    return render(authentication, model, draft(session), "kopf", null);
  }

  @PostMapping("/kopf")
  public String submitHeader(
      @RequestParam(required = false) String invoiceNumber,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String issueDate,
      @RequestParam(required = false) String dueDate,
      @RequestParam(required = false) String deliveryDate,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String orderReference,
      @RequestParam(required = false) String supplierNumber,
      Authentication authentication,
      HttpSession session,
      Model model) {
    InvoiceDraft draft = draft(session);
    draft.setInvoiceNumber(invoiceNumber);
    draft.setType(type);
    draft.setIssueDate(issueDate);
    draft.setDueDate(dueDate);
    draft.setDeliveryDate(deliveryDate);
    draft.setCurrency(currency);
    draft.setOrderReference(orderReference);
    draft.setSupplierNumber(supplierNumber);

    if (!StringUtils.hasText(draft.getInvoiceNumber().trim())) {
      return render(authentication, model, draft, "kopf", "Eine Rechnungsnummer ist erforderlich.");
    }
    if (!StringUtils.hasText(draft.getIssueDate())) {
      return render(authentication, model, draft, "kopf", "Ein Rechnungsdatum ist erforderlich.");
    }
    return "redirect:/app/rechnungen/neu/parteien";
  }

  // -------------------------------------------------------------- 2. Parteien

  @GetMapping("/parteien")
  public String partiesStep(Authentication authentication, HttpSession session, Model model) {
    return render(authentication, model, draft(session), "parteien", null);
  }

  @PostMapping("/parteien")
  public String submitParties(
      @RequestParam(required = false) String sellerName,
      @RequestParam(required = false) String sellerVatId,
      @RequestParam(required = false) String sellerEmail,
      @RequestParam(required = false) String sellerStreet,
      @RequestParam(required = false) String sellerCity,
      @RequestParam(required = false) String sellerPostalCode,
      @RequestParam(required = false) String sellerCountryCode,
      @RequestParam(required = false) String buyerName,
      @RequestParam(required = false) String buyerVatId,
      @RequestParam(required = false) String buyerStreet,
      @RequestParam(required = false) String buyerCity,
      @RequestParam(required = false) String buyerPostalCode,
      @RequestParam(required = false) String buyerCountryCode,
      Authentication authentication,
      HttpSession session,
      Model model) {
    InvoiceDraft draft = draft(session);
    draft.setSellerName(sellerName);
    draft.setSellerVatId(sellerVatId);
    draft.setSellerEmail(sellerEmail);
    draft.setSellerStreet(sellerStreet);
    draft.setSellerCity(sellerCity);
    draft.setSellerPostalCode(sellerPostalCode);
    draft.setSellerCountryCode(sellerCountryCode);
    draft.setBuyerName(buyerName);
    draft.setBuyerVatId(buyerVatId);
    draft.setBuyerStreet(buyerStreet);
    draft.setBuyerCity(buyerCity);
    draft.setBuyerPostalCode(buyerPostalCode);
    draft.setBuyerCountryCode(buyerCountryCode);

    if (!StringUtils.hasText(draft.getSellerName().trim())
        || !StringUtils.hasText(draft.getBuyerName().trim())) {
      return render(
          authentication,
          model,
          draft,
          "parteien",
          "Name von Rechnungssteller und Empfänger sind erforderlich.");
    }
    return "redirect:/app/rechnungen/neu/positionen";
  }

  // ------------------------------------------------------------ 3. Positionen

  @GetMapping("/positionen")
  public String linesStep(Authentication authentication, HttpSession session, Model model) {
    return render(authentication, model, draft(session), "positionen", null);
  }

  @PostMapping("/positionen/hinzufuegen")
  public String addLine(
      @RequestParam(required = false) String description,
      @RequestParam(required = false) String quantity,
      @RequestParam(required = false) String unitCode,
      @RequestParam(required = false) String unitPrice,
      @RequestParam(required = false) String vatCategory,
      @RequestParam(required = false) String vatPercent,
      Authentication authentication,
      HttpSession session,
      Model model) {
    InvoiceDraft draft = draft(session);
    if (!StringUtils.hasText(description)
        || !StringUtils.hasText(quantity)
        || !StringUtils.hasText(unitPrice)) {
      return render(
          authentication,
          model,
          draft,
          "positionen",
          "Beschreibung, Menge und Einzelpreis sind für eine Position erforderlich.");
    }
    draft.addLine(
        new InvoiceDraft.Line(
            description,
            quantity,
            StringUtils.hasText(unitCode) ? unitCode : "C62",
            unitPrice,
            StringUtils.hasText(vatCategory) ? vatCategory : "STANDARD",
            StringUtils.hasText(vatPercent) ? vatPercent : "20"));
    return "redirect:/app/rechnungen/neu/positionen";
  }

  @PostMapping("/positionen/{index}/entfernen")
  public String removeLine(@PathVariable int index, HttpSession session) {
    draft(session).removeLine(index);
    return "redirect:/app/rechnungen/neu/positionen";
  }

  @PostMapping("/positionen")
  public String submitLines(Authentication authentication, HttpSession session, Model model) {
    InvoiceDraft draft = draft(session);
    if (!draft.hasLines()) {
      // core enforces this too; catching it here puts the message on the step that can fix it.
      return render(
          authentication,
          model,
          draft,
          "positionen",
          "Eine Rechnung braucht mindestens eine Position.");
    }
    return "redirect:/app/rechnungen/neu/zahlung";
  }

  // -------------------------------------------------- 4. Zahlung + anlegen

  @GetMapping("/zahlung")
  public String paymentStep(Authentication authentication, HttpSession session, Model model) {
    return render(authentication, model, draft(session), "zahlung", null);
  }

  /**
   * The last step: serialise the draft and create the invoice through the same service {@code POST
   * /api/v1/invoices} uses.
   *
   * <p>Three failure kinds are translated into a message on this page instead of a status code, and
   * each of them is something the user can actually fix here:
   *
   * <ul>
   *   <li>{@link DuplicateInvoiceException} — the number is taken (409 on the API);
   *   <li>{@link InvariantViolationException} — a domain rule, e.g. the IBAN checksum (422 on the
   *       API). Its message is safe to echo: {@code core} writes bounded messages for exactly this;
   *   <li>{@link InvoiceJsonException} — a shape error, which here means a value could not be
   *       parsed (an amount, a date, a currency code).
   * </ul>
   *
   * <p>On success the draft is removed from the session, so reopening the wizard starts empty
   * rather than pre-filled with the invoice just created — which would invite a duplicate number.
   */
  @PostMapping("/anlegen")
  public String create(
      @RequestParam(required = false) String iban,
      @RequestParam(required = false) String bic,
      @RequestParam(required = false) String paymentTerms,
      Authentication authentication,
      HttpSession session,
      Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    InvoiceDraft draft = draft(session);
    draft.setIban(iban);
    draft.setBic(bic);
    draft.setPaymentTerms(paymentTerms);

    try {
      InvoiceCreated created =
          invoices.create(tenant.getId(), draft.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
      session.removeAttribute(DRAFT);
      return "redirect:/app/rechnungen/" + created.id();
    } catch (DuplicateInvoiceException e) {
      return render(
          authentication,
          model,
          draft,
          "zahlung",
          "Es gibt bereits eine Rechnung mit der Nummer "
              + draft.getInvoiceNumber().trim()
              + ". Ändern Sie die Nummer im Schritt „Kopfdaten“.");
    } catch (InvariantViolationException e) {
      // core's messages are bounded by design (they never echo an unbounded value) and they name
      // the
      // rule that failed, which is the most useful thing the user can be told here.
      return render(
          authentication, model, draft, "zahlung", "Die Rechnung ist ungültig: " + e.getMessage());
    } catch (InvoiceJsonException e) {
      return render(
          authentication,
          model,
          draft,
          "zahlung",
          "Ein Wert konnte nicht gelesen werden: " + e.getMessage());
    }
  }

  // -------------------------------------------------------------------- helpers

  /** The session's draft, created on first use. */
  private static InvoiceDraft draft(HttpSession session) {
    Object existing = session.getAttribute(DRAFT);
    if (existing instanceof InvoiceDraft draft) {
      return draft;
    }
    InvoiceDraft fresh = new InvoiceDraft();
    session.setAttribute(DRAFT, fresh);
    return fresh;
  }

  private String render(
      Authentication authentication, Model model, InvoiceDraft draft, String step, String error) {
    model.addAttribute("tenantName", currentTenant.require(authentication).getDisplayName());
    model.addAttribute("draft", draft);
    model.addAttribute("step", step);
    model.addAttribute("formError", error);
    return "app/wizard";
  }
}
