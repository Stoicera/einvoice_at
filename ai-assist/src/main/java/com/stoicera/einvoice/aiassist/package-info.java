/**
 * AI-assisted explanation of validation findings behind the {@code features.ai-explanations} flag:
 * {@code LlmClient} port, OpenRouter adapter, versioned prompt templates.
 *
 * <p>Only called from {@code app}; the platform must remain fully functional when the provider is
 * unavailable. PII is scrubbed before any LLM call.
 */
package com.stoicera.einvoice.aiassist;
