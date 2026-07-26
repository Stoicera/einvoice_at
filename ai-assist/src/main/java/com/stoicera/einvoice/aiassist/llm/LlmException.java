package com.stoicera.einvoice.aiassist.llm;

/**
 * Every way a completion can fail, as one checked exception.
 *
 * <p><strong>Checked deliberately.</strong> An LLM call is a network call to a third party that
 * this platform explicitly does not depend on (ENGINEERING_STANDARDS §8: AI features degrade).
 * Making failure part of {@link LlmClient#complete}'s signature means a caller cannot forget the
 * degradation path — the compiler asks about it. An unchecked exception would let a missed {@code
 * catch} turn a provider outage into a 500 on a page that should have rendered the report and a
 * friendly notice.
 *
 * <p>{@link #isRetryable()} distinguishes "this might work if tried again" (a timeout, a connection
 * reset, HTTP 429 or 5xx) from "this will never work" (HTTP 400/401/403 — a bad request or a bad
 * credential). Retrying the latter wastes the caller's latency budget and, on a 401, hammers a
 * provider with a credential that is not going to become valid.
 */
public class LlmException extends Exception {

  private static final long serialVersionUID = 1L;

  private final boolean retryable;

  public LlmException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  public LlmException(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  /** Whether an identical retry has any chance of succeeding; see the class Javadoc. */
  public boolean isRetryable() {
    return retryable;
  }
}
