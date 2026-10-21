package com.stoicera.einvoice.validation.repair;

/**
 * Replace {@code length} characters at {@code offset} of the original text with {@code
 * replacement}; a pure insertion has length 0.
 *
 * <p>Edits are made on the text, not on a re-serialized DOM, so that everything the proposal does
 * not touch stays byte-for-byte as uploaded — formatting, comments, attribute order, namespace
 * prefixes — and the diff shows only the change.
 */
record TextEdit(int offset, int length, String replacement) {

  TextEdit {
    if (offset < 0 || length < 0) {
      throw new IllegalArgumentException("offset and length must not be negative");
    }
  }

  static TextEdit insert(int offset, String text) {
    return new TextEdit(offset, 0, text);
  }

  int end() {
    return offset + length;
  }
}
