package com.stoicera.einvoice.mapping.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.validation.Finding;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The format strings here are the ones production actually produces — {@code ebinterface} and
 * {@code ubl}, the published {@code POST /convert?from=&to=} vocabulary.
 *
 * <p>They used to be {@code ebinterface-6.1} / {@code ubl-2.1}: values no caller ever passes,
 * borrowed from a {@code TargetFormat.id()} that turned out to have no call sites at all. A test
 * exercising a value space the shipped code never enters cannot catch a regression in it, which is
 * why the M4 hostile review (finding F7) counted this as a test gap rather than cosmetics.
 */
class ConversionReportTest {

  private static final String FROM = "ebinterface";
  private static final String TO = "ubl";

  private static final Finding LOSS = ConversionNotes.lost("x", "verloren", "lost");
  private static final Finding CONVENTION = ConversionNotes.convention("x", "übersetzt", "mapped");
  private static final Finding DEVIATION =
      ConversionNotes.derivedTotalMismatch("x", "abweichend", "deviating");

  @Test
  void aReportWithNoNotesIsLosslessAndTrustworthy() {
    ConversionReport report = new ConversionReport(FROM, TO, List.of());

    assertThat(report.sourceFormat()).isEqualTo(FROM);
    assertThat(report.targetFormat()).isEqualTo(TO);
    assertThat(report.notes()).isEmpty();
    assertThat(report.isLossless()).isTrue();
    assertThat(report.isTrustworthy()).isTrue();
  }

  /**
   * The distinction the whole record exists to make: dropping a field the target has no concept of
   * is normal and leaves the result usable. Only a changed amount makes a conversion untrustworthy.
   */
  @Test
  void aLossyConversionIsStillTrustworthy() {
    ConversionReport report = report(LOSS, CONVENTION);

    assertThat(report.isLossless()).isFalse();
    assertThat(report.isTrustworthy()).isTrue();
  }

  @Test
  void aDeviationMakesTheConversionUntrustworthy() {
    ConversionReport report = report(LOSS, DEVIATION);

    assertThat(report.isLossless()).isFalse();
    assertThat(report.isTrustworthy()).isFalse();
  }

  @Test
  void notesKeepTheOrderTheyWereProducedIn() {
    assertThat(report(CONVENTION, LOSS, DEVIATION).notes())
        .containsExactly(CONVENTION, LOSS, DEVIATION);
  }

  @Test
  void notesAreDefensivelyCopiedAndImmutable() {
    List<Finding> mutable = new ArrayList<>();
    mutable.add(LOSS);

    ConversionReport report = new ConversionReport(FROM, TO, mutable);
    mutable.add(DEVIATION); // must not leak into the report

    assertThat(report.notes()).containsExactly(LOSS);
    assertThatThrownBy(() -> report.notes().add(DEVIATION))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ConversionReport report(Finding... notes) {
    return new ConversionReport(FROM, TO, List.of(notes));
  }
}
