package com.stoicera.einvoice.app.convert;

import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds {@code ?from=ebinterface&to=ubl} to {@link ConversionService.ConversionFormat}.
 *
 * <p>Spring's default enum binding matches the Java constant <em>name</em>, so without this the API
 * would have to be called with {@code ?from=EBINTERFACE} — shouting at the caller to suit an
 * implementation detail. The query-string spelling is part of the published contract (SPEC §4
 * writes it as {@code from=ebinterface&to=ubl}), so it is the enum's {@code id()} that binds here,
 * matched case-insensitively because no caller should have to guess.
 *
 * <p>An unknown value throws {@link IllegalArgumentException}, which Spring surfaces as a 400 with
 * the project's problem+json vocabulary — the right answer for a value that is simply not a format
 * this API knows.
 */
@Component
class ConversionFormatConverter implements Converter<String, ConversionService.ConversionFormat> {

  @Override
  public ConversionService.ConversionFormat convert(String source) {
    String normalised = source.trim().toLowerCase(Locale.ROOT);
    for (ConversionService.ConversionFormat format : ConversionService.ConversionFormat.values()) {
      if (format.id().equals(normalised)) {
        return format;
      }
    }
    throw new IllegalArgumentException(
        "Unknown invoice format '%s'; expected one of ebinterface, ubl.".formatted(normalised));
  }
}
