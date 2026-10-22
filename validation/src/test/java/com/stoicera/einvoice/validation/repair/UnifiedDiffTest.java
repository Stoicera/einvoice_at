package com.stoicera.einvoice.validation.repair;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {

  /** Ten numbered lines, "l1".."l10", newline-terminated. */
  private static final String TEN =
      IntStream.rangeClosed(1, 10).mapToObj(i -> "l" + i + "\n").collect(Collectors.joining());

  private static int endOfLine(String text, int line) {
    int offset = -1;
    for (int i = 0; i < line; i++) {
      offset = text.indexOf('\n', offset + 1);
    }
    return offset;
  }

  @Test
  void anInsertedLineIsOneAddedLineWithThreeLinesOfContext() {
    String diff =
        UnifiedDiff.render(TEN, List.of(TextEdit.insert(endOfLine(TEN, 5), "\nnew")), "a", "b");

    assertThat(diff)
        .isEqualTo(
            """
            --- a/a
            +++ b/b
            @@ -3,6 +3,7 @@
             l3
             l4
             l5
            +new
             l6
             l7
             l8
            """);
  }

  @Test
  void aReplacementWithinALineShowsTheLineRemovedAndAdded() {
    int at = TEN.indexOf("l2");
    String diff = UnifiedDiff.render(TEN, List.of(new TextEdit(at, 2, "L2")), "a", "b");

    assertThat(diff)
        .isEqualTo(
            """
            --- a/a
            +++ b/b
            @@ -1,5 +1,5 @@
             l1
            -l2
            +L2
             l3
             l4
             l5
            """);
  }

  @Test
  void distantEditsGetTwoHunksAndTheSecondHunkCountsTheFirstInsertion() {
    String text =
        IntStream.rangeClosed(1, 20).mapToObj(i -> "l" + i + "\n").collect(Collectors.joining());
    String diff =
        UnifiedDiff.render(
            text,
            List.of(
                TextEdit.insert(endOfLine(text, 15), "\nsecond"),
                TextEdit.insert(endOfLine(text, 2), "\nfirst")),
            "a",
            "b");

    assertThat(diff).contains("@@ -1,5 +1,6 @@").contains("@@ -13,6 +14,7 @@");
    assertThat(diff.indexOf("+first")).isLessThan(diff.indexOf("+second"));
  }

  @Test
  void nearbyEditsShareOneHunk() {
    String diff =
        UnifiedDiff.render(
            TEN,
            List.of(
                TextEdit.insert(endOfLine(TEN, 3), "\nx"),
                TextEdit.insert(endOfLine(TEN, 6), "\ny")),
            "a",
            "b");

    assertThat(diff).containsOnlyOnce("@@ -").contains("@@ -1,9 +1,11 @@");
  }

  @Test
  void twoEditsOnOneLineAreRenderedTogether() {
    int a = TEN.indexOf("l4");
    String diff =
        UnifiedDiff.render(
            TEN, List.of(new TextEdit(a, 1, "L"), new TextEdit(a + 1, 1, "X")), "a", "b");

    assertThat(diff).contains("-l4\n+LX\n").containsOnlyOnce("@@ -");
  }

  @Test
  void crLfLinesKeepTheirCarriageReturnSoThePatchAppliesToTheFile() {
    String text = TEN.replace("\n", "\r\n");
    String diff =
        UnifiedDiff.render(
            text, List.of(TextEdit.insert(endOfLine(text, 1) - 1, "\r\nnew")), "a", "b");

    // As git prints it: headers end in LF, every content line keeps the file's CR.
    assertThat(diff)
        .startsWith("--- a/a\n+++ b/b\n@@ -1,4 +1,5 @@\n")
        .contains(" l1\r\n+new\r\n l2\r\n");
  }
}
