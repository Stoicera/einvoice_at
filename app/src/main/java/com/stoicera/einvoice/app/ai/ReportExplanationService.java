package com.stoicera.einvoice.app.ai;

import com.stoicera.einvoice.aiassist.ExplanationContext;
import com.stoicera.einvoice.app.invoice.InvoiceParties;
import com.stoicera.einvoice.app.invoice.InvoiceService;
import com.stoicera.einvoice.app.report.ReportDetail;
import com.stoicera.einvoice.app.report.ReportService;
import com.stoicera.einvoice.core.validation.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Explains the findings of a <strong>stored</strong> report — the service behind {@code POST
 * /api/v1/reports/{id}/explain} (SPEC §4) and the dashboard's report view.
 *
 * <h2>How this differs from the public route, and why both exist</h2>
 *
 * <p>{@code PublicWebController.erklaeren} takes the finding's text as form fields, because an
 * anonymous report has no id: nothing was stored. Here the opposite holds — the report is a row, so
 * the findings are read <em>from the row</em> and the caller supplies only which report and
 * optionally which finding. Two consequences follow, and both are improvements:
 *
 * <ul>
 *   <li>the caller cannot choose the text to be explained, so this route cannot be used as a
 *       general "explain this string for me" endpoint at the operator's expense;
 *   <li>there <em>is</em> an invoice behind an invoice-tied report, so its seller and buyer names
 *       can be handed to the scrubber as literals to redact — the one thing the public page
 *       structurally cannot do ({@code ExplanationContext.withPartyNames} exists for exactly this
 *       call).
 * </ul>
 *
 * <h2>Bounded by design</h2>
 *
 * <p>One request explains at most {@code app.ai.max-findings-per-request} findings, errors first. A
 * Peppol report can carry dozens of findings and each distinct one is a paid call, so an unbounded
 * "explain everything" would turn a single POST into an unbounded bill. Findings past the cap keep
 * a {@code null} explanation, which is already that field's meaning.
 *
 * <p>Nothing is written: explaining is a read plus a provider call. The stored report keeps saying
 * what the validator said, and the explanation is a view over it.
 */
@Service
public class ReportExplanationService {

  private final ReportService reports;
  private final InvoiceService invoices;
  private final ExplanationService explanations;
  private final int maxFindingsPerRequest;

  public ReportExplanationService(
      ReportService reports,
      InvoiceService invoices,
      ExplanationService explanations,
      @Value("${app.ai.max-findings-per-request:10}") int maxFindingsPerRequest) {
    this.reports = reports;
    this.invoices = invoices;
    this.explanations = explanations;
    this.maxFindingsPerRequest = maxFindingsPerRequest;
  }

  /**
   * Returns the tenant's report with AI explanations attached to its findings.
   *
   * @param findingIndex the single finding to explain, or empty to explain up to the configured cap
   * @throws com.stoicera.einvoice.app.report.ReportNotFoundException the report is not this
   *     tenant's
   * @throws AiExplanationsDisabledException the feature is switched off on this deployment
   * @throws InvalidFindingIndexException {@code findingIndex} is not a position in this report
   * @throws AiExplanationUnavailableException explanations were requested and none could be
   *     produced
   */
  public ReportDetail explain(UUID tenantId, UUID reportId, OptionalInt findingIndex) {
    if (!explanations.isEnabled()) {
      // Checked before the report is even read, so a disabled deployment answers the same 503
      // whether or not the id happens to exist — except for the tenant boundary, which the read
      // below still enforces for a caller who does pass a real id. Order matters here: reading
      // first would let a caller probe ids while the feature is off and get a 404/503 oracle.
      throw new AiExplanationsDisabledException();
    }

    ReportDetail report = reports.get(tenantId, reportId);
    List<Finding> findings = report.findings();
    List<Integer> targets = targetsFor(findings, findingIndex, maxFindingsPerRequest);
    if (targets.isEmpty()) {
      return report; // nothing to explain: a clean report, not a failure
    }

    ExplanationContext context = contextFor(report, partiesFor(tenantId, report));
    List<Finding> explained = new ArrayList<>(findings);
    int produced = 0;
    for (int index : targets) {
      Optional<String> explanation = explanations.explain(findings.get(index), context);
      if (explanation.isPresent()) {
        explained.set(index, findings.get(index).withAiExplanation(explanation.get()));
        produced++;
      }
    }
    if (produced == 0) {
      throw new AiExplanationUnavailableException(targets.size());
    }

    return new ReportDetail(
        report.id(),
        report.invoiceId(),
        report.sourceFormat(),
        report.profile(),
        report.valid(),
        List.copyOf(explained),
        report.createdAt());
  }

  /**
   * The positions to explain: exactly the requested one, or the most severe {@code
   * maxFindingsPerRequest} of them.
   *
   * <p>Positions rather than findings, because the result has to be reassembled in the report's
   * original order — a caller reading the {@code findings} array must see the same sequence {@code
   * GET /reports/{id}} returned, with some entries decorated. Sorting the array itself would
   * silently reorder the report.
   */
  static List<Integer> targetsFor(List<Finding> findings, OptionalInt findingIndex, int cap) {
    if (findingIndex.isPresent()) {
      int index = findingIndex.getAsInt();
      if (index < 0 || index >= findings.size()) {
        throw new InvalidFindingIndexException(index, findings.size());
      }
      return List.of(index);
    }
    // Sorting the INDICES, never the findings: mapping sorted findings back with List.indexOf would
    // collapse duplicates onto the first equal element, and duplicate findings are normal — one
    // rule
    // fires once per offending line (FindingView's Javadoc records the same hazard for the UI).
    // Stream.sorted is stable, so findings of equal severity keep ascending index order.
    return java.util.stream.IntStream.range(0, findings.size())
        .boxed()
        .sorted(Comparator.comparingInt(index -> findings.get(index).severity().ordinal()))
        .limit(Math.max(cap, 0))
        .toList();
  }

  /**
   * Builds the context a finding is explained in, attaching the invoice's party names when there is
   * an invoice to take them from.
   *
   * <p>Static and separated from the call so it can be asserted directly: "an invoice-tied report
   * redacts its parties, an ad-hoc one has nothing to redact" is the privacy claim of this class,
   * and it should not have to be inferred from bytes on a wire.
   */
  static ExplanationContext contextFor(ReportDetail report, InvoiceParties parties) {
    return parties == null
        ? ExplanationContext.of(report.sourceFormat(), report.profile())
        : ExplanationContext.withPartyNames(
            report.sourceFormat(), report.profile(), parties.sellerName(), parties.buyerName());
  }

  /**
   * The parties of the invoice this report belongs to, or {@code null} for an ad-hoc validation.
   *
   * <p>An {@code invoiceId} pointing at a row this tenant cannot read would be a broken invariant
   * rather than a caller error (the report itself was already tenant-checked), so the lookup is
   * allowed to throw — but a missing invoice must not cost the caller their explanation, so it
   * degrades to "no literals to redact" rather than failing the request.
   */
  private InvoiceParties partiesFor(UUID tenantId, ReportDetail report) {
    if (report.invoiceId() == null) {
      return null;
    }
    try {
      return invoices.parties(tenantId, report.invoiceId());
    } catch (RuntimeException e) {
      return null;
    }
  }
}
