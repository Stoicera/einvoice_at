package com.stoicera.einvoice.mapping.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.validation.Finding;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversionReportTest {

  private static final Finding LOSS = ConversionNotes.lost("x", "verloren", "lost");
  private static final Finding CONVENTION = ConversionNotes.convention("x", "übersetzt", "mapped");
  private static final Finding DEVIATION =
      ConversionNotes.derivedTotalMismatch("x", "abweichend", "deviating");

  @Test
  void aReportWithNoNotesIsLosslessAndTrustworthy() {
    ConversionReport report = ConversionReport.lossless("ebinterface-6.1", "ubl-2.1");

    assertThat(report.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(report.targetFormat()).isEqualTo("ubl-2.1");
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
  void plusAppendsNotesKeepingTheFormats() {
    ConversionReport combined =
        ConversionReport.lossless("ebinterface-6.1", "ubl-2.1")
            .plus(List.of(LOSS))
            .plus(List.of(DEVIATION));

    assertThat(combined.notes()).containsExactly(LOSS, DEVIATION);
    assertThat(combined.sourceFormat()).isEqualTo("ebinterface-6.1");
    assertThat(combined.targetFormat()).isEqualTo("ubl-2.1");
    assertThat(combined.isTrustworthy()).isFalse();
  }

  @Test
  void notesAreDefensivelyCopiedAndImmutable() {
    List<Finding> mutable = new ArrayList<>();
    mutable.add(LOSS);

    ConversionReport report = new ConversionReport("a", "b", mutable);
    mutable.add(DEVIATION); // must not leak into the report

    assertThat(report.notes()).containsExactly(LOSS);
    assertThatThrownBy(() -> report.notes().add(DEVIATION))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ConversionReport report(Finding... notes) {
    return new ConversionReport("ebinterface-6.1", "ubl-2.1", List.of(notes));
  }
}
