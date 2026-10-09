package com.stoicera.einvoice.app.web;

import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Makes the API's problem {@code type} URIs followable (RFC 9457 §3.1.1: a {@code type} should
 * resolve to human-readable documentation).
 *
 * <p>Every {@code type} is {@code Problems.BASE} plus a slug, and the default base is this host's
 * {@code /problems/}. Each slug redirects to its entry in {@code docs/problems.md}, so the
 * documentation lives in one place — the repository, versioned with the code that emits the slugs —
 * instead of a second copy rendered here.
 *
 * <p>The redirect target is a fixed host and path; the slug only becomes the fragment, and only
 * when it has the shape every real slug has. Anything else is a plain 404, so this is not an open
 * redirect and not a reflection point.
 */
@Controller
public class ProblemTypeController {

  static final String DOCS = "https://github.com/Stoicera/einvoice_at/blob/main/docs/problems.md";

  /** Every slug the API emits is lower-case words joined by hyphens. */
  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+){0,7}");

  @GetMapping({"/problems", "/problems/"})
  public ResponseEntity<Void> index() {
    return redirect(DOCS);
  }

  @GetMapping("/problems/{slug}")
  public ResponseEntity<Void> problemType(@PathVariable String slug) {
    if (!SLUG.matcher(slug).matches()) {
      return ResponseEntity.notFound().build();
    }
    return redirect(DOCS + "#" + slug);
  }

  private static ResponseEntity<Void> redirect(String target) {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
  }
}
