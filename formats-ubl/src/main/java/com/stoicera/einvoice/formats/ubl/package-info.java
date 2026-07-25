/**
 * Peppol BIS Billing 3.0 (UBL 2.1) read/write, wrapping ph-ubl.
 *
 * <p>A standards-only adapter, exactly like {@code formats-ebinterface}: it turns bytes into the
 * UBL JAXB tree and back, and nothing else. It must not import Spring, and must not depend on the
 * canonical {@code core} model — canonical mapping happens in {@code mapping}. Enforced by {@code
 * FormatsUblArchitectureTest}.
 *
 * <p>Scope note: Peppol BIS Billing 3.0 is a <em>customisation of</em> UBL 2.1, not a syntax of its
 * own. Everything BIS adds on top of the schema is Schematron, which this module deliberately does
 * not run — the official OpenPeppol rule sets are executed by the {@code validation} module through
 * phive, at a pinned rule-set version. So "reads UBL" here means "reads the syntax"; "is this a
 * valid Peppol invoice?" is a question only {@code validation} answers.
 */
package com.stoicera.einvoice.formats.ubl;
