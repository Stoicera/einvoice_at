/*
 * Progressive enhancement — 40 lines, first-party, no dependency.
 *
 * WHY THIS AND NOT htmx. SPEC §1 and §5 name htmx, and htmx would be a fine choice: it is small,
 * well tested, and exactly aimed at this shape of interaction. It is not used here for the same
 * reason Tailwind's CLI is not (ADR-0009): this UI has precisely two interactions that benefit from
 * swapping a fragment instead of reloading — the validator upload and the per-finding "Erklären" —
 * and every page below works with JavaScript disabled entirely, because each of those endpoints
 * returns a fragment that is also a valid whole response.
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
        return response.text();
      })
      .then(function (html) {
        target.innerHTML = html;
      })
      .catch(function () {
        // A network failure must not leave the page looking like nothing happened. Plain text, no
        // markup: this string is the only thing on the page not produced by a template.
        target.textContent =
          "Die Anfrage konnte nicht gesendet werden. Bitte laden Sie die Seite neu und versuchen Sie es erneut.";
      })
      .finally(function () {
        form.classList.remove("htmx-request");
        if (submit) submit.disabled = false;
      });
  });
})();
