package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.security.ApiKeyService;
import com.stoicera.einvoice.app.security.CurrentTenant;
import com.stoicera.einvoice.app.security.TooManyApiKeysException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The dashboard's API-key page: mint, list, revoke — the browser counterpart of {@code
 * /api/v1/api-keys}, through the same {@link ApiKeyService} and therefore the same cap, the same
 * audit events and the same atomicity.
 *
 * <h2>The plaintext key travels as a flash attribute, and that is the whole design</h2>
 *
 * <p>Only a key's hash is stored, so the page has exactly one opportunity to show the secret.
 * Putting it in the model and rendering the page straight from the POST would display it correctly
 * — and then display it again on every reload, and leave it in the browser's resubmit-this-form
 * state. A POST-redirect-GET with a flash attribute shows it once and cannot show it twice, because
 * a flash survives exactly one redirect. {@code ApiKeyPageIT} asserts both halves: that the
 * redirect target renders it, and that a plain reload does not.
 *
 * <p>{@code POST} + redirect also means the browser's back button and refresh never re-mint a key.
 * No JavaScript is involved in any of this (ADR-0009): the page is three forms.
 */
@Controller
@RequestMapping("/app/api-schluessel")
public class ApiKeyPageController {

  /** Same bound the API's request DTO applies, so the two surfaces refuse the same names. */
  private static final int MAX_NAME_LENGTH = 100;

  private final ApiKeyService apiKeys;
  private final CurrentTenant currentTenant;

  public ApiKeyPageController(ApiKeyService apiKeys, CurrentTenant currentTenant) {
    this.apiKeys = apiKeys;
    this.currentTenant = currentTenant;
  }

  @GetMapping
  public String page(Authentication authentication, Model model) {
    TenantEntity tenant = currentTenant.require(authentication);
    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("keys", apiKeys.list(tenant.getId()));
    return "app/api-keys";
  }

  /**
   * Mints a key and redirects, carrying the plaintext in the flash scope.
   *
   * <p>Validation failures re-render the page with a message rather than redirecting, so the
   * message is attached to the attempt that caused it. {@link TooManyApiKeysException} is caught
   * here for the same reason: on the API it is a 409 problem document, which is right there and
   * useless in a browser.
   */
  @PostMapping
  public String create(
      @RequestParam(required = false) String name,
      Authentication authentication,
      Model model,
      RedirectAttributes flash) {
    TenantEntity tenant = currentTenant.require(authentication);

    String trimmed = name == null ? "" : name.trim();
    if (!StringUtils.hasText(trimmed)) {
      return rerenderWithError(tenant, model, "Bitte geben Sie einen Namen für den Schlüssel an.");
    }
    if (trimmed.length() > MAX_NAME_LENGTH) {
      return rerenderWithError(
          tenant, model, "Der Name darf höchstens " + MAX_NAME_LENGTH + " Zeichen lang sein.");
    }

    try {
      ApiKeyService.CreatedKey created = apiKeys.create(tenant.getId(), trimmed);
      flash.addFlashAttribute("createdKey", created.generated().plaintext());
      flash.addFlashAttribute("createdKeyName", trimmed);
    } catch (TooManyApiKeysException e) {
      return rerenderWithError(
          tenant,
          model,
          "Sie haben bereits die maximale Anzahl aktiver Schlüssel ("
              + e.getLimit()
              + "). Widerrufen Sie einen, um Platz zu schaffen.");
    }
    return "redirect:/app/api-schluessel";
  }

  /**
   * Revokes a key (soft: the row is kept, stamped) and redirects back to the list.
   *
   * <p>{@code ApiKeyNotFoundException} for another tenant's id is deliberately not caught: {@link
   * WebExceptionHandler} turns it into the same 404 page an unknown id gets, so the page cannot be
   * used to discover that some id exists elsewhere.
   */
  @PostMapping("/{id}/widerrufen")
  public String revoke(@PathVariable UUID id, Authentication authentication) {
    TenantEntity tenant = currentTenant.require(authentication);
    apiKeys.revoke(tenant.getId(), id);
    return "redirect:/app/api-schluessel";
  }

  private String rerenderWithError(TenantEntity tenant, Model model, String message) {
    model.addAttribute("tenantName", tenant.getDisplayName());
    model.addAttribute("keys", apiKeys.list(tenant.getId()));
    model.addAttribute("formError", message);
    return "app/api-keys";
  }
}
