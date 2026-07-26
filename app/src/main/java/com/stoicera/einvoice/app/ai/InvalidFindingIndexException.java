package com.stoicera.einvoice.app.ai;

/**
 * The {@code findingIndex} the caller asked to explain is not a position in that report's findings
 * list — mapped to {@code 400} + {@code invalid-finding-index}.
 *
 * <p>Refused rather than clamped or ignored. Silently explaining finding 0 when the caller asked
 * for finding 99 would answer a question nobody asked and bill for it; silently explaining nothing
 * would look identical to a provider outage.
 */
public class InvalidFindingIndexException extends RuntimeException {

  private final int index;
  private final int findingCount;

  public InvalidFindingIndexException(int index, int findingCount) {
    super(
        "findingIndex "
            + index
            + " is out of range for a report with "
            + findingCount
            + " findings");
    this.index = index;
    this.findingCount = findingCount;
  }

  public int getIndex() {
    return index;
  }

  public int getFindingCount() {
    return findingCount;
  }
}
