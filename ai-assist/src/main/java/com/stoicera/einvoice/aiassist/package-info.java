/**
 * AI-assisted explanation of validation findings, behind the {@code features.ai-explanations} flag:
 * the {@link com.stoicera.einvoice.aiassist.llm.LlmClient} port, the OpenRouter adapter, versioned
 * prompt templates, and {@link com.stoicera.einvoice.aiassist.FindingExplainer} on top of them.
 *
 * <p>Only called from {@code app}. Three properties are load-bearing and enforced by tests rather
 * than by convention:
 *
 * <ul>
 *   <li><strong>Spring-free.</strong> Like every other library module, this one is plain Java —
 *       {@code app} owns the wiring. {@code AiAssistArchitectureTest} pins it.
 *   <li><strong>Degradable.</strong> The platform must stay fully functional when the provider is
 *       unavailable, so nothing here throws out of {@link
 *       com.stoicera.einvoice.aiassist.FindingExplainer#explain}: a failure is an empty {@link
 *       java.util.Optional}, and the caller renders the report without an explanation.
 *   <li><strong>PII is scrubbed before any call leaves the JVM.</strong> Validator messages quote
 *       document values verbatim — that is the whole point of a Schematron message — so the finding
 *       text reaching a third-party provider is masked by {@link
 *       com.stoicera.einvoice.aiassist.internal.PiiScrubber} first.
 * </ul>
 */
package com.stoicera.einvoice.aiassist;
