package com.stoicera.einvoice.app.api;

/**
 * A value supplied for a correction proposal cannot be written into an e-invoice: too long for its
 * field, or a character XML 1.0 cannot carry. Answered 400 {@code invalid-repair-input}.
 */
public class InvalidRepairInputException extends RuntimeException {

  public InvalidRepairInputException(String message) {
    super(message);
  }
}
