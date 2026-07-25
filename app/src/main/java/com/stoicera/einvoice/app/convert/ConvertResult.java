package com.stoicera.einvoice.app.convert;

import com.stoicera.einvoice.core.validation.ValidationReport;
import com.stoicera.einvoice.mapping.conversion.ConversionReport;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code POST /api/v1/convert} answers with: the converted document, what the conversion cost,
 * and whether the result is actually acceptable to the target standard.
 *
 * <p>The third field is the one that makes the endpoint useful rather than merely functional. A
 * converter that hands back XML and leaves the caller to discover at an access point that it fails
 * Peppol has done half a job; running the output through the validator here means the answer to
 * "can I send this?" arrives with the document.
 *
 * @param conversion every loss, convention translation and deviation the conversion produced
 * @param xml the converted document
 * @param report the validation report of that converted document, against the target format's own
 *     profile
 */
@Schema(
    description = "A converted invoice, its conversion notes, and the validation of the result.")
public record ConvertResult(ConversionReport conversion, String xml, ValidationReport report) {}
