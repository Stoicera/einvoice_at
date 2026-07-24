/**
 * Mapping between the canonical model and the concrete formats (ebInterface 6.1, UBL BIS 3.0).
 *
 * <p>All mapping is hand-written and heavily tested; MapStruct was evaluated (see SPEC §1) but is
 * unused as of M2 — every mapping so far is semantic, not mechanical. Golden-file tests are the
 * contract. Must not import Spring.
 */
package com.stoicera.einvoice.mapping;
