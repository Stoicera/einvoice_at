/**
 * Internal — not API.
 *
 * <p>The mechanics {@link com.stoicera.einvoice.aiassist.FindingExplainer} is built from: PII
 * masking, prompt-template rendering, and the explanation cache. Types here are public only so the
 * module's own tests can exercise them directly — a scrubber whose only test goes through an HTTP
 * client is not really tested. Nothing outside {@code com.stoicera.einvoice.aiassist} may import
 * them, which {@code AiAssistArchitectureTest} enforces.
 */
package com.stoicera.einvoice.aiassist.internal;
