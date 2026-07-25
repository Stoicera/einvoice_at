package com.stoicera.einvoice.app.convert;

/**
 * The requested conversion cannot be performed as asked — the source and target formats are the
 * same, or the upload is not in the format the caller declared.
 *
 * <p>A domain exception rather than a {@code ResponseStatusException}: services throw domain
 * exceptions and {@code ApiExceptionHandler} owns the HTTP status, a rule ArchUnit enforces since
 * the M3 hostile review found a service smuggling a status out through the framework's own type.
 */
public class UnsupportedConversionException extends RuntimeException {

  public UnsupportedConversionException(String message) {
    super(message);
  }
}
