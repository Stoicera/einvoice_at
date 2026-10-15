package com.stoicera.einvoice.app.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The crawler policy and the sitemap, both anonymous (the web filter chain permits the two paths
 * explicitly) and both rendered rather than static, because both have to name the public host: the
 * {@code Sitemap:} directive and every {@code <loc>} must be absolute URLs, and a static file
 * cannot know whether it is served from the public demo or from a self-hosted instance.
 *
 * <p>The policy itself is unchanged from the static {@code robots.txt} it replaces: the public
 * pages ARE the point — PRD §2 makes the validator a lead magnet and MILESTONES M5 asks for German
 * SEO meta — so the landing page and the validator are explicitly open to indexing. Everything
 * behind a login is not; those URLs answer a redirect to Keycloak for a crawler anyway, and listing
 * them keeps them out of the index rather than relying on that. The sitemap lists exactly the two
 * indexable pages, nothing a crawler should not have.
 */
@RestController
class CrawlerController {

  private final PublicSite site;

  CrawlerController(PublicSite site) {
    this.site = site;
  }

  @GetMapping(value = "/robots.txt", produces = "text/plain;charset=utf-8")
  String robots() {
    return """
        User-agent: *
        Allow: /$
        Allow: /validator
        Disallow: /app/
        Disallow: /api/
        Disallow: /actuator/
        Disallow: /swagger-ui/
        Disallow: /v3/api-docs

        Sitemap: %s
        """
        .formatted(site.url("/sitemap.xml"));
  }

  @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
  String sitemap() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          <url><loc>%s</loc></url>
          <url><loc>%s</loc></url>
        </urlset>
        """
        .formatted(site.url("/"), site.url("/validator"));
  }
}
