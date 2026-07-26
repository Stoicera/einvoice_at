/**
 * The provider-independent LLM port: {@link com.stoicera.einvoice.aiassist.llm.LlmClient} and the
 * value types it speaks.
 *
 * <p><strong>Boundary contract:</strong> nothing in this package knows what a validation finding
 * is, and nothing in it knows what OpenRouter is. It is the narrow abstraction
 * ENGINEERING_STANDARDS §8 requires ("Provider-Zugriff ausschließlich über eine eigene schmale
 * Abstraktion"), so a second provider is a second class in {@code ..aiassist.openrouter}'s sibling
 * package and nothing above the port changes.
 */
package com.stoicera.einvoice.aiassist.llm;
