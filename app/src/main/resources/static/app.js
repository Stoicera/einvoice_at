/*
 * Progressive enhancement — first-party, no dependency, about twenty lines of actual logic.
 *
 * WHY THIS AND NOT htmx. htmx would be a fine choice: it is small, well tested, and exactly aimed at
 * this shape of interaction. It is not used here for the same reason Tailwind's CLI is not
 * (ADR-0009 Entscheidung 5): this UI has precisely two interactions that benefit from swapping a
 * fragment instead of reloading — the validator upload and the per-finding "Erklären" — and every
 * page below works with JavaScript disabled entirely, because each of those endpoints returns a
 * fragment that is also a valid whole response.
 *
 * So the choice is between vendoring a library whose bytes have to be kept current and verified, and
 * owning the twenty lines that cover the two cases. The second is smaller, reviewable in a diff, and
 * has no supply chain. If the UI grows past this, swapping in real htmx is a script tag and deleting
 * this file — the markup contract (a form with data-swap="#target") is a subset of htmx's own.
 *
 * The contract:
 *   <form data-swap="#target">  submits by fetch and replaces #target's innerHTML with the response
 *   [data-spinner]              shown while that form's request is in flight (CSS does the showing)
 *
 * Without this file: the form posts normally, the browser navigates, the fragment is the page.
 */
(function () {
  "use strict";

  /** A non-2xx response, carried out of the promise chain so the message can name the case. */
  function HttpError(status) {
    this.status = status;
  }

  /**
   * What the user is told. German, specific where being specific helps them act, and generic
   * otherwise — a status code on screen helps nobody who is not reading the source.
   */
  function messageFor(error) {
    if (!(error instanceof HttpError)) {
      return "Die Anfrage konnte nicht gesendet werden. Bitte laden Sie die Seite neu und versuchen Sie es erneut.";
    }
    if (error.status === 429) {
      return "Zu viele Anfragen in kurzer Zeit. Bitte warten Sie einen Moment und versuchen Sie es erneut.";
    }
    if (error.status === 403) {
      return "Die Sitzung ist abgelaufen. Bitte laden Sie die Seite neu.";
    }
    if (error.status === 413) {
      return "Die Datei ist zu groß. Erlaubt sind bis zu 2 MB.";
    }
    return "Die Anfrage ist fehlgeschlagen. Bitte laden Sie die Seite neu und versuchen Sie es erneut.";
  }

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!(form instanceof HTMLFormElement)) return;

    var selector = form.getAttribute("data-swap");
    if (!selector) return;

    var target = document.querySelector(selector);
    if (!target) return; // no target in this document — let the browser submit normally

    event.preventDefault();

    var submit = form.querySelector('button[type="submit"]');
    form.classList.add("htmx-request"); // same class name as htmx, so the CSS needs no second rule
    if (submit) submit.disabled = true;

    fetch(form.action, {
      method: (form.method || "post").toUpperCase(),
      body: new FormData(form),
      // Same-origin only, and credentials included so the CSRF cookie and session travel with a
      // dashboard form. A cross-origin swap is not something this contract should ever do.
      credentials: "same-origin",
      headers: { "X-Requested-With": "fetch" },
    })
      .then(function (response) {
        // ONLY a 2xx body is markup this contract may inject. Without this check every error
        // response was swapped into the page as HTML: a 429 rendered its problem+json as visible
        // JSON inside the report card, and a 500 nested a whole error page in it. The likely
        // failures were the unhandled ones, while the unlikely one below was handled carefully.
        if (!response.ok) {
          throw new HttpError(response.status);
        }
        return response.text();
      })
      .then(function (html) {
        target.innerHTML = html;
      })
      .catch(function (error) {
        // A failure must not leave the page looking like nothing happened. textContent, never
        // innerHTML: these strings are the only thing on the page not produced by a template, and
        // nothing that came off the wire is rendered as markup here.
        target.textContent = messageFor(error);
      })
      .finally(function () {
        form.classList.remove("htmx-request");
        if (submit) submit.disabled = false;
      });
  });
})();
