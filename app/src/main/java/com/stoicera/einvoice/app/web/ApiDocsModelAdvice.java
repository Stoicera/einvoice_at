package com.stoicera.einvoice.app.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Publishes whether the Swagger UI is served, so the templates can stop linking to it when it is
 * not.
 *
 * <h2>Why this is an advice and not another {@code model.addAttribute} call</h2>
 *
 * <p>{@code aiEnabled} is added per handler method, which works because the AI controls appear on
 * two pages. The API link is different: it sits in {@code fragments/layout.html}, so it renders on
 * <em>every</em> page this application serves — including the error pages. Adding the attribute
 * method by method would mean one forgotten handler puts the dead link back, which is exactly the
 * defect being fixed here. A {@link ControllerAdvice} scoped to this package cannot be forgotten.
 *
 * <p>Note the failure direction: where the attribute is absent the expression is {@code null},
 * {@code th:if} treats it as false, and the link is omitted. A wiring mistake therefore hides a
 * working link rather than advertising a missing one — the harmless way round, and {@code
 * OpenApiIT.thePublicPagesLinkToTheApiDocs} pins the other direction so the omission cannot become
 * permanent by accident.
 *
 * <p>Bound to {@code springdoc.swagger-ui.enabled} — the property that actually decides whether the
 * UI exists — rather than to {@code API_DOCS_ENABLED}, the environment variable that happens to
 * drive it today. One flag drives the document, the UI and now the links, which is what {@code
 * application.yml} already claimed when it said the two "can never disagree about being exposed".
 */
@ControllerAdvice(basePackages = "com.stoicera.einvoice.app.web")
class ApiDocsModelAdvice {

  private final boolean apiDocsEnabled;

  ApiDocsModelAdvice(@Value("${springdoc.swagger-ui.enabled:true}") boolean apiDocsEnabled) {
    this.apiDocsEnabled = apiDocsEnabled;
  }

  @ModelAttribute("apiDocsEnabled")
  boolean apiDocsEnabled() {
    return apiDocsEnabled;
  }
}
