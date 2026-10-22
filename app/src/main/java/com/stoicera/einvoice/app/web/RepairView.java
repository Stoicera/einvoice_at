package com.stoicera.einvoice.app.web;

import com.stoicera.einvoice.validation.repair.ProposedChange;
import com.stoicera.einvoice.validation.repair.RepairInput;
import com.stoicera.einvoice.validation.repair.RepairProposal;
import java.util.Base64;
import java.util.List;

/**
 * A {@link RepairProposal} prepared for the report fragment, so the template carries no logic of
 * its own: per change a status and its label, the diff split into classified lines, and — only when
 * a change takes user input — the uploaded document for the follow-up form.
 *
 * <h2>Why the document travels back to the browser</h2>
 *
 * <p>An anonymous upload is not stored (ADR-0006), so when the user types the Auftragsreferenz
 * there is nothing on the server to apply it to. The alternative to sending the document back in a
 * hidden field would be keeping it in the session — retention by another name, on the one page that
 * promises none. The document is the visitor's own and already on their machine; Base64 keeps its
 * bytes exact whatever its encoding. The cost is stated plainly: a document over about 1.5 MB no
 * longer fits the 2 MB request cap once encoded, and the follow-up then answers 413.
 *
 * @param proposal the proposal
 * @param changes each change with its display status
 * @param diffLines the unified diff, one classified line per entry; empty without a correction
 * @param document the uploaded bytes as Base64 when a change takes input, else {@code null}
 * @param orderReference the Auftragsreferenz the proposal used, to pre-fill the form; may be null
 */
public record RepairView(
    RepairProposal proposal,
    List<ChangeView> changes,
    List<DiffLine> diffLines,
    String document,
    String orderReference) {

  /** One change and how the re-validation judged it. */
  public record ChangeView(ProposedChange change, String statusClass, String statusLabel) {

    /**
     * The value as shown: with its identifier scheme where it has one, a dash when there is none.
     */
    public String displayValue() {
      if (change.value() == null) {
        return "—";
      }
      return change.schemeId() == null
          ? change.value()
          : change.value() + " (Schema " + change.schemeId() + ")";
    }
  }

  /**
   * One line of the diff. {@code kind} is the CSS suffix: {@code add}, {@code del}, {@code hunk},
   * {@code meta} or {@code ctx}.
   */
  public record DiffLine(String kind, String text) {}

  static RepairView of(RepairProposal proposal, byte[] uploaded, RepairInput input) {
    List<ChangeView> changes =
        proposal.changes().stream().map(change -> changeView(proposal, change)).toList();
    boolean takesInput = proposal.changes().stream().anyMatch(c -> c.inputName() != null);
    return new RepairView(
        proposal,
        changes,
        diffLines(proposal.diff()),
        takesInput ? Base64.getEncoder().encodeToString(uploaded) : null,
        input.orderReference());
  }

  /** Whether the re-validation of the corrected document came back without an error. */
  public boolean revalidatedValid() {
    return proposal.revalidatedValid();
  }

  private static ChangeView changeView(RepairProposal proposal, ProposedChange change) {
    if (!change.applied()) {
      return new ChangeView(change, "warn", "Eingabe erforderlich");
    }
    return proposal.resolves(change)
        ? new ChangeView(change, "ok", "behoben")
        : new ChangeView(change, "error", "nicht behoben");
  }

  static List<DiffLine> diffLines(String diff) {
    if (diff == null) {
      return List.of();
    }
    return diff.lines().map(RepairView::classify).toList();
  }

  private static DiffLine classify(String line) {
    String kind;
    if (line.startsWith("+++") || line.startsWith("---")) {
      kind = "meta";
    } else if (line.startsWith("@@")) {
      kind = "hunk";
    } else if (line.startsWith("+")) {
      kind = "add";
    } else if (line.startsWith("-")) {
      kind = "del";
    } else {
      kind = "ctx";
    }
    return new DiffLine(kind, line);
  }
}
