/**
 * The OpenRouter adapter — this platform's default {@link
 * com.stoicera.einvoice.aiassist.llm.LlmClient} implementation.
 *
 * <p><strong>Boundary contract:</strong> this is the only package in the repository that knows
 * OpenRouter exists, and nothing outside it may import from here except {@code app}'s wiring. A
 * second provider becomes a sibling package implementing the same port; nothing above the port
 * changes. OpenRouter speaks the OpenAI-compatible {@code /chat/completions} shape, so a
 * LiteLLM-compatible endpoint or a self-hosted gateway is reachable by pointing the base URL at it
 * — which is the substitutability ENGINEERING_STANDARDS §8 asks for, without a second adapter.
 */
package com.stoicera.einvoice.aiassist.openrouter;
