package com.stoicera.einvoice.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.persistence.ApiKeyEntity;
import com.stoicera.einvoice.app.persistence.ApiKeyRepository;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import com.stoicera.einvoice.app.security.ApiKeys;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The dashboard's API-key page — the browser counterpart of {@code /api/v1/api-keys}.
 *
 * <p>Two properties carry the weight here, and both are about a secret:
 *
 * <ul>
 *   <li><strong>The plaintext key is shown exactly once.</strong> It is never stored (only its hash
 *       is), so the page has one chance to display it. It travels as a <em>flash</em> attribute
 *       through a POST-redirect-GET, which means a reload of the resulting page cannot re-show it —
 *       asserted, because holding it in the model and rendering the form directly would look
 *       identical until someone pressed F5 and found their secret still on screen.
 *   <li><strong>CSRF is enforced.</strong> Minting and revoking credentials from a cookie session
 *       is exactly what the token exists for, so a token-less POST must be refused.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyPageIT extends AbstractPostgresIT {

  private static final String PAGE = "/app/api-schluessel";

  @Autowired private MockMvc mvc;
  @Autowired private TenantRepository tenants;
  @Autowired private ApiKeyRepository apiKeys;

  // ---------------------------------------------------------------------- listing

  @Test
  void thePageListsTheTenantsKeysWithoutAnySecret() throws Exception {
    String sub = sub("list");
    TenantEntity tenant = tenant(sub);
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(tenant.getId(), "CI-Pipeline", generated.keyHash(), generated.prefix()));

    mvc.perform(get(PAGE).with(login(sub)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CI-Pipeline")))
        .andExpect(content().string(containsString(generated.prefix())))
        // Neither the plaintext nor the hash may ever be rendered in a listing. The prefix is the
        // deliberate exception: it is what lets a user tell two keys apart.
        .andExpect(content().string(not(containsString(generated.plaintext()))))
        .andExpect(content().string(not(containsString(generated.keyHash()))));
  }

  @Test
  void anEmptyKeyListSaysSo() throws Exception {
    String sub = sub("empty");
    tenant(sub);

    mvc.perform(get(PAGE).with(login(sub)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Noch keine API-Schlüssel")));
  }

  @Test
  void aTenantsPageNeverShowsAnotherTenantsKey() throws Exception {
    String sub = sub("isolation");
    tenant(sub);
    TenantEntity other = tenant(sub("isolation-other"));
    ApiKeys.GeneratedKey theirs = ApiKeys.generate();
    apiKeys.save(
        new ApiKeyEntity(other.getId(), "Fremder-Schlüssel", theirs.keyHash(), theirs.prefix()));

    mvc.perform(get(PAGE).with(login(sub)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Fremder-Schlüssel"))))
        .andExpect(content().string(not(containsString(theirs.prefix()))));
  }

  // ---------------------------------------------------------------------- creating

  @Test
  void creatingAKeyRedirectsAndCarriesThePlaintextExactlyOnce() throws Exception {
    String sub = sub("create");
    tenant(sub);

    String plaintext =
        (String)
            mvc.perform(post(PAGE).param("name", "Mein Schlüssel").with(login(sub)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE))
                .andExpect(flash().attributeExists("createdKey"))
                .andReturn()
                .getFlashMap()
                .get("createdKey");

    Assertions.assertThat(plaintext).isNotBlank();

    // The redirect target renders it when the flash attribute is present …
    mvc.perform(get(PAGE).with(login(sub)).flashAttr("createdKey", plaintext))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(plaintext)))
        .andExpect(content().string(containsString("nur dieses eine Mal")));

    // … and a plain reload — no flash attribute, because a flash survives exactly one redirect —
    // does not. This is the assertion that makes "exactly once" true rather than aspirational.
    mvc.perform(get(PAGE).with(login(sub)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString(plaintext))));
  }

  @Test
  void theCreatedKeyIsStoredHashedAndUsableForTheTenant() throws Exception {
    String sub = sub("create-stored");
    TenantEntity tenant = tenant(sub);

    mvc.perform(post(PAGE).param("name", "Produktiv").with(login(sub)).with(csrf()))
        .andExpect(status().is3xxRedirection());

    Assertions.assertThat(apiKeys.findByTenantIdOrderByCreatedAtDesc(tenant.getId()))
        .singleElement()
        .satisfies(
            key -> {
              Assertions.assertThat(key.getName()).isEqualTo("Produktiv");
              Assertions.assertThat(key.isRevoked()).isFalse();
            });
  }

  @Test
  void aBlankKeyNameIsRefusedWithAMessageRatherThanA500() throws Exception {
    String sub = sub("create-blank");
    tenant(sub);

    mvc.perform(post(PAGE).param("name", "   ").with(login(sub)).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Bitte geben Sie einen Namen")));
  }

  @Test
  void creatingWithoutTheCsrfTokenIsRefused() throws Exception {
    String sub = sub("create-nocsrf");
    TenantEntity tenant = tenant(sub);

    mvc.perform(post(PAGE).param("name", "Ohne Token").with(login(sub)))
        .andExpect(status().isForbidden());

    // And nothing was minted — a 403 that still wrote the row would be the worst of both.
    Assertions.assertThat(apiKeys.findByTenantIdOrderByCreatedAtDesc(tenant.getId())).isEmpty();
  }

  // ---------------------------------------------------------------------- revoking

  @Test
  void revokingAKeyMarksItRevokedAndKeepsTheRow() throws Exception {
    String sub = sub("revoke");
    TenantEntity tenant = tenant(sub);
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    ApiKeyEntity key =
        apiKeys.save(
            new ApiKeyEntity(
                tenant.getId(), "Zu widerrufen", generated.keyHash(), generated.prefix()));

    mvc.perform(post(PAGE + "/" + key.getId() + "/widerrufen").with(login(sub)).with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(PAGE));

    // Soft revoke: the row stays for the audit trail, stamped rather than deleted.
    Assertions.assertThat(apiKeys.findById(key.getId()))
        .get()
        .satisfies(revoked -> Assertions.assertThat(revoked.isRevoked()).isTrue());
  }

  @Test
  void aTenantCannotRevokeAnotherTenantsKey() throws Exception {
    String sub = sub("revoke-foreign");
    tenant(sub);
    TenantEntity other = tenant(sub("revoke-foreign-other"));
    ApiKeys.GeneratedKey theirs = ApiKeys.generate();
    ApiKeyEntity key =
        apiKeys.save(new ApiKeyEntity(other.getId(), "Fremd", theirs.keyHash(), theirs.prefix()));

    mvc.perform(post(PAGE + "/" + key.getId() + "/widerrufen").with(login(sub)).with(csrf()))
        .andExpect(status().isNotFound());

    Assertions.assertThat(apiKeys.findById(key.getId()))
        .get()
        .satisfies(untouched -> Assertions.assertThat(untouched.isRevoked()).isFalse());
  }

  @Test
  void revokingWithoutTheCsrfTokenIsRefused() throws Exception {
    String sub = sub("revoke-nocsrf");
    TenantEntity tenant = tenant(sub);
    ApiKeys.GeneratedKey generated = ApiKeys.generate();
    ApiKeyEntity key =
        apiKeys.save(
            new ApiKeyEntity(tenant.getId(), "Bleibt", generated.keyHash(), generated.prefix()));

    mvc.perform(post(PAGE + "/" + key.getId() + "/widerrufen").with(login(sub)))
        .andExpect(status().isForbidden());

    Assertions.assertThat(apiKeys.findById(key.getId()))
        .get()
        .satisfies(untouched -> Assertions.assertThat(untouched.isRevoked()).isFalse());
  }

  // ----------------------------------------------------------------------- helpers

  private static String sub(String test) {
    return "api-key-page-" + test;
  }

  private static RequestPostProcessor login(String sub) {
    return oauth2Login().attributes(attributes -> attributes.put("sub", sub));
  }

  private TenantEntity tenant(String sub) {
    return tenants
        .findByExternalSubject(sub)
        .orElseGet(() -> tenants.save(new TenantEntity(sub, "Schlüssel-Mandant " + sub)));
  }

  /** Guards against a stray id colliding with a real one in a shared database. */
  @Test
  void revokingAnUnknownKeyIsNotFound() throws Exception {
    String sub = sub("revoke-unknown");
    tenant(sub);

    mvc.perform(post(PAGE + "/" + UUID.randomUUID() + "/widerrufen").with(login(sub)).with(csrf()))
        .andExpect(status().isNotFound());
  }
}
