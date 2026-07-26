package com.stoicera.einvoice.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link FindingView} — the small amount of logic that stands between a {@code Finding} and the
 * report template, so the template carries no vocabulary and no arithmetic of its own.
 *
 * <p>The index is the part worth pinning. A rule id is <strong>not</strong> unique within a report:
 * a document missing both parties' electronic addresses reports two different rules, and a document
 * with three malformed lines reports the same rule three times. Both the "Erklären" button and the
 * dashboard's explain route key on the position, so an index that drifted from the report's own
 * order would explain the wrong finding — quietly, and only for reports with duplicates.
 */
class FindingViewTest {

  @Test
  void indicesAreAssignedInReportOrder() {
    List<FindingView> views =
        FindingView.of(
            List.of(
                finding(Severity.ERROR, "AT-B2G-01"),
                finding(Severity.WARN, "PEPPOL-EN16931-R020"),
                finding(Severity.INFO, "CONV-02")));

    assertThat(views).hasSize(3);
    assertThat(views.get(0).index()).isZero();
    assertThat(views.get(1).index()).isEqualTo(1);
    assertThat(views.get(2).index()).isEqualTo(2);
  }

  @Test
  void repeatedRuleIdsKeepDistinctIndices() {
    // The case the class Javadoc exists for: one rule firing once per offending line.
    List<FindingView> views =
        FindingView.of(
            List.of(
                finding(Severity.ERROR, "BR-CO-10"),
                finding(Severity.ERROR, "BR-CO-10"),
                finding(Severity.ERROR, "BR-CO-10")));

    assertThat(views.stream().map(FindingView::index)).containsExactly(0, 1, 2);
  }

  @Test
  void filteringBySeverityPreservesThePositionInTheFullList() {
    // The load-bearing property of ofSeverity: the template renders three grouped lists, and the
    // button in the "Warnungen" group must still name the warning's position in the WHOLE report.
    List<FindingView> all =
        FindingView.of(
            List.of(
                finding(Severity.INFO, "CONV-01"),
                finding(Severity.ERROR, "AT-B2G-01"),
                finding(Severity.WARN, "AT-B2G-04"),
                finding(Severity.ERROR, "AT-B2G-03")));

    assertThat(FindingView.ofSeverity(all, Severity.ERROR).stream().map(FindingView::index))
        .containsExactly(1, 3);
    assertThat(FindingView.ofSeverity(all, Severity.WARN).stream().map(FindingView::index))
        .containsExactly(2);
    assertThat(FindingView.ofSeverity(all, Severity.INFO).stream().map(FindingView::index))
        .containsExactly(0);
  }

  @Test
  void anEmptyReportProducesNoViews() {
    assertThat(FindingView.of(List.of())).isEmpty();
    assertThat(FindingView.ofSeverity(List.of(), Severity.ERROR)).isEmpty();
  }

  @Test
  void everySeverityHasAGermanLabelAndACssClass() {
    // The template has no vocabulary of its own, so every enum constant must be answered here —
    // a missing case would be a blank pill rather than an error.
    for (Severity severity : Severity.values()) {
      FindingView view = new FindingView(0, finding(severity, "X-1"));

      assertThat(view.severityLabel()).isNotBlank();
      assertThat(view.severityClass())
          .isEqualTo(severity.name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  @Test
  void theGermanLabelsAreTheOnesTheReportShows() {
    assertThat(new FindingView(0, finding(Severity.ERROR, "X")).severityLabel())
        .isEqualTo("Fehler");
    assertThat(new FindingView(0, finding(Severity.WARN, "X")).severityLabel())
        .isEqualTo("Warnung");
    assertThat(new FindingView(0, finding(Severity.INFO, "X")).severityLabel())
        .isEqualTo("Hinweis");
  }

  @Test
  void hasExplanationIsFalseUntilOneIsAttached() {
    FindingView plain = new FindingView(0, finding(Severity.ERROR, "AT-B2G-01"));

    assertThat(plain.hasExplanation()).isFalse();
  }

  @Test
  void hasExplanationIsTrueOnceOneIsAttached() {
    FindingView explained =
        new FindingView(
            0, finding(Severity.ERROR, "AT-B2G-01").withAiExplanation("Die Referenz fehlt."));

    assertThat(explained.hasExplanation()).isTrue();
  }

  @Test
  void aBlankExplanationCountsAsAbsent() {
    // Otherwise the template would render an empty "KI-Erklärung" box and hide the button that
    // could
    // have produced a real one.
    FindingView blank =
        new FindingView(0, finding(Severity.ERROR, "AT-B2G-01").withAiExplanation("   "));

    assertThat(blank.hasExplanation()).isFalse();
  }

  private static Finding finding(Severity severity, String ruleId) {
    return Finding.of(severity, ruleId, "/Invoice", "Deutsche Meldung", "English message");
  }
}
