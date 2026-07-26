package com.stoicera.einvoice.aiassist.llm;

/**
 * The port every LLM provider is reached through — the "eigene schmale Abstraktion"
 * ENGINEERING_STANDARDS §8 mandates, and the only type the rest of the platform is allowed to know
 * about.
 *
 * <p>One method, deliberately. Streaming, tool use, embeddings and conversation state are all
 * things a general-purpose LLM abstraction would offer and this platform has no use for: it asks
 * one self-contained question per finding and wants one paragraph back. An interface with
 * capabilities nobody calls is speculative generality, and it is also the fastest way to make a
 * second provider expensive to add.
 *
 * <p>Implementations must be safe for concurrent use — {@code app} holds a single instance as a
 * bean and serves requests from the container's thread pool.
 */
public interface LlmClient {

  /**
   * Runs one completion.
   *
   * @param prompt what to ask
   * @return the answer and its usage
   * @throws LlmException the provider could not be reached, refused the request, answered
   *     unintelligibly, or took longer than the configured timeout. Callers of an AI feature must
   *     treat this as normal and degrade (see the module's {@code package-info}); {@link
   *     LlmException#isRetryable()} says whether an identical retry has any chance.
   */
  LlmCompletion complete(LlmPrompt prompt) throws LlmException;
}
