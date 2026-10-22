package com.stoicera.einvoice.validation.repair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Renders a set of {@link TextEdit}s as a unified diff (the format of {@code diff -u} and {@code
 * git diff}), three lines of context.
 *
 * <p>Built from the edits rather than by diffing two texts: the proposer knows exactly what it
 * changed, so there is nothing to discover, and a general line diff over a 2 MB document would cost
 * time for an answer that is already known. Each edit is widened to the whole lines it touches;
 * lines the edit leaves identical at either end are then trimmed off again, so inserting a line
 * after {@code <cac:Party>} shows as one added line, not as that tag removed and re-added.
 *
 * <p>Content lines keep their own line end: in a CR LF document each line carries its {@code \r},
 * as {@code git diff} prints it, so {@code git apply} and {@code patch} accept the diff for the
 * file it describes. Header lines end in LF, also as git does. A viewer that wants plain lines
 * splits with {@link String#lines()}, which treats CR LF as one terminator.
 */
final class UnifiedDiff {

  private static final int CONTEXT = 3;

  private UnifiedDiff() {}

  /** One changed region: original lines {@code [from, to)} become {@code added}. */
  private record Change(int from, int to, List<String> added) {}

  static String render(String original, List<TextEdit> edits, String oldName, String newName) {
    int[] lineStarts = lineStarts(original);
    List<String> lines = lines(original, lineStarts);

    // Edits that touch a common line are rendered together, or their hunks would overlap.
    List<List<TextEdit>> clusters = new ArrayList<>();
    int clusterLastLine = -1;
    for (TextEdit edit :
        edits.stream().sorted(Comparator.comparingInt(TextEdit::offset)).toList()) {
      int firstLine = lineOf(lineStarts, edit.offset());
      if (clusters.isEmpty() || firstLine > clusterLastLine) {
        clusters.add(new ArrayList<>());
      }
      clusters.getLast().add(edit);
      clusterLastLine = Math.max(clusterLastLine, lineOf(lineStarts, edit.end()));
    }
    List<Change> changes = new ArrayList<>();
    for (List<TextEdit> cluster : clusters) {
      changes.add(change(original, lineStarts, lines, cluster));
    }

    StringBuilder out = new StringBuilder();
    out.append("--- a/").append(oldName).append('\n');
    out.append("+++ b/").append(newName).append('\n');

    int delta = 0; // new-side line numbers run ahead of old-side ones by this much
    int i = 0;
    while (i < changes.size()) {
      // Gather every change whose context overlaps the previous one into one hunk.
      int j = i;
      while (j + 1 < changes.size()
          && changes.get(j + 1).from() - changes.get(j).to() <= 2 * CONTEXT) {
        j++;
      }
      int hunkFrom = Math.max(0, changes.get(i).from() - CONTEXT);
      int hunkTo = Math.min(lines.size(), changes.get(j).to() + CONTEXT);

      StringBuilder body = new StringBuilder();
      int oldCount = 0;
      int newCount = 0;
      int cursor = hunkFrom;
      int hunkDelta = 0;
      for (int k = i; k <= j; k++) {
        Change change = changes.get(k);
        for (; cursor < change.from(); cursor++, oldCount++, newCount++) {
          body.append(' ').append(lines.get(cursor)).append('\n');
        }
        for (; cursor < change.to(); cursor++, oldCount++) {
          body.append('-').append(lines.get(cursor)).append('\n');
        }
        for (String added : change.added()) {
          body.append('+').append(added).append('\n');
          newCount++;
        }
        hunkDelta += change.added().size() - (change.to() - change.from());
      }
      for (; cursor < hunkTo; cursor++, oldCount++, newCount++) {
        body.append(' ').append(lines.get(cursor)).append('\n');
      }

      out.append("@@ -")
          .append(range(hunkFrom, oldCount))
          .append(" +")
          .append(range(hunkFrom + delta, newCount))
          .append(" @@\n")
          .append(body);
      delta += hunkDelta;
      i = j + 1;
    }
    return out.toString();
  }

  /** The change a cluster of edits (sorted, touching a common run of lines) makes. */
  private static Change change(
      String text, int[] lineStarts, List<String> lines, List<TextEdit> cluster) {
    int first = lineOf(lineStarts, cluster.getFirst().offset());
    int last = cluster.stream().mapToInt(e -> lineOf(lineStarts, e.end())).max().orElse(first);
    int blockStart = lineStarts[first];
    int blockEnd = last + 1 < lineStarts.length ? lineStarts[last + 1] : text.length();

    StringBuilder block = new StringBuilder();
    int cursor = blockStart;
    for (TextEdit edit : cluster) {
      block.append(text, cursor, edit.offset()).append(edit.replacement());
      cursor = edit.end();
    }
    String replaced = block.append(text, cursor, blockEnd).toString();
    List<String> before = new ArrayList<>(lines.subList(first, last + 1));
    List<String> after = new ArrayList<>(splitLines(replaced));

    // Trim the lines the edit left as they were, at both ends.
    int from = first;
    while (!before.isEmpty() && !after.isEmpty() && before.getFirst().equals(after.getFirst())) {
      before.removeFirst();
      after.removeFirst();
      from++;
    }
    while (!before.isEmpty() && !after.isEmpty() && before.getLast().equals(after.getLast())) {
      before.removeLast();
      after.removeLast();
    }
    return new Change(from, from + before.size(), List.copyOf(after));
  }

  /**
   * {@code start,count} in unified-diff notation (1-based; an empty range names the line before).
   */
  private static String range(int zeroBasedStart, int count) {
    int start = count == 0 ? zeroBasedStart : zeroBasedStart + 1;
    return start + "," + count;
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n' && i + 1 < text.length()) {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static int lineOf(int[] lineStarts, int offset) {
    int index = Arrays.binarySearch(lineStarts, offset);
    return index >= 0 ? index : -index - 2;
  }

  private static List<String> lines(String text, int[] lineStarts) {
    List<String> lines = new ArrayList<>(lineStarts.length);
    for (int i = 0; i < lineStarts.length; i++) {
      int end = i + 1 < lineStarts.length ? lineStarts[i + 1] : text.length();
      lines.add(stripLineBreak(text.substring(lineStarts[i], end)));
    }
    return lines;
  }

  /** Splits a block of whole lines; a trailing line break does not start a further, empty line. */
  private static List<String> splitLines(String block) {
    List<String> out = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < block.length(); i++) {
      if (block.charAt(i) == '\n') {
        out.add(stripLineBreak(block.substring(start, i + 1)));
        start = i + 1;
      }
    }
    if (start < block.length()) {
      out.add(stripLineBreak(block.substring(start)));
    }
    return out;
  }

  /** Drops the LF; a CR before it stays part of the line (see the class Javadoc). */
  private static String stripLineBreak(String line) {
    return line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
  }
}
