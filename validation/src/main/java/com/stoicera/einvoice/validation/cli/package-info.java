/**
 * {@code ValidationRunner}: a plain-Java command-line front end for {@code InvoiceValidator}.
 *
 * <p><strong>Boundary contract.</strong> No Spring, no logging framework — this package exists so
 * the validator can run standalone via {@code exec:java} (README "Validation pipeline" quickstart)
 * without pulling in an application context, keeping golden-file-corpus smoke runs fast. All output
 * goes through the {@link java.io.PrintStream}s passed to the testable {@code run} method; {@code
 * main} is the only place that touches {@link System#out}/{@link System#err} or calls {@link
 * System#exit}, translating a validation outcome into the documented exit codes ({@code 0}/{@code
 * 1}/{@code 2}). This package constructs no {@link com.stoicera.einvoice.core.validation.Finding}s
 * of its own — it only prints the already German-first, already-bounded findings the pipeline
 * produced.
 */
package com.stoicera.einvoice.validation.cli;
