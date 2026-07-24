package com.stoicera.einvoice.core.validation;

/**
 * Severity of a single {@link Finding}, ordered most to least severe.
 *
 * <p>{@code ERROR} means the document violates a hard rule (schema, Schematron, or business rule)
 * and is not compliant; {@code WARN} flags a discouraged-but-legal construct; {@code INFO} is an
 * informational note that does not affect compliance. {@link ValidationReport#isValid()} depends
 * only on the presence of an {@code ERROR} finding.
 */
public enum Severity {
  ERROR,
  WARN,
  INFO
}
