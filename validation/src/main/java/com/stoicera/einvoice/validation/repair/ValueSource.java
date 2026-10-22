package com.stoicera.einvoice.validation.repair;

/**
 * Where the value of a {@link ProposedChange} comes from — the provenance shown with every change.
 */
public enum ValueSource {

  /** Copied from another element of the same document; the element is named in the change. */
  DOCUMENT("aus dem Dokument"),

  /** Typed by the user in the correction form or passed as an API parameter. */
  USER_INPUT("Ihre Eingabe"),

  /**
   * Not available: neither in the document nor supplied. The change is listed but <em>not</em>
   * written into the document, so a placeholder can never pass validation by accident.
   */
  PLACEHOLDER("Platzhalter — Eingabe erforderlich");

  private final String labelDe;

  ValueSource(String labelDe) {
    this.labelDe = labelDe;
  }

  /** German label for the report. */
  public String labelDe() {
    return labelDe;
  }
}
