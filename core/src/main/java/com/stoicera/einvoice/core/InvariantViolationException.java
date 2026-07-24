package com.stoicera.einvoice.core;

/** Thrown when a domain object would violate an invariant of the canonical invoice model. */
public class InvariantViolationException extends IllegalArgumentException {

  public InvariantViolationException(String message) {
    super(message);
  }
}
