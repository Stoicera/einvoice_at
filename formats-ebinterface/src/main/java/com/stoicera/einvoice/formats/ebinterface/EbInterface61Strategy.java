package com.stoicera.einvoice.formats.ebinterface;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.list.ErrorList;
import com.helger.ebinterface.EEbInterfaceVersion;
import com.helger.ebinterface.EbInterface61Marshaller;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.jaxb.GenericJAXBMarshaller;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ebInterface 6.1 read/write over ph-ebinterface's {@link EbInterface61Marshaller}.
 *
 * <p>The marshaller ships with the ebInterface 6.1 XSD and validates against it by default. This
 * adapter deliberately turns that off ({@code setUseSchema(false)}): schema and Schematron
 * validation are the validation module's job, not this one. Reads are therefore lenient — a
 * structurally broken document yields a failure with collected diagnostics rather than a thrown
 * exception, and a technically-incomplete tree can still be written.
 *
 * <p>JAXB marshallers are not thread-safe, so a fresh instance is created per call; this class
 * holds no mutable state and is safe to share.
 */
public final class EbInterface61Strategy implements EbInterfaceVersionStrategy<Ebi61InvoiceType> {

  private static GenericJAXBMarshaller<Ebi61InvoiceType> newMarshaller() {
    // setUseSchema(false): validation lives in the validation module, not here.
    return new EbInterface61Marshaller().setUseSchema(false);
  }

  @Override
  public String namespaceUri() {
    return EEbInterfaceVersion.V61.getNamespaceURI();
  }

  @Override
  public ReadResult<Ebi61InvoiceType> read(byte[] xml) {
    ErrorList errorList = new ErrorList();
    Ebi61InvoiceType document = newMarshaller().setCollectErrors(errorList).read(xml);

    List<String> errors = new ArrayList<>();
    for (IError error : errorList) {
      errors.add(error.getAsStringLocaleIndepdent());
    }

    // Without schema validation, JAXB still returns a (near-empty) tree for well-formed XML whose
    // root element is not ebInterface 6.1, but reports the mismatch as an error. Treat any
    // collected
    // error-level diagnostic as a failed read so callers get a null document, not a hollow one.
    if (errorList.containsAtLeastOneError()) {
      document = null;
    }

    return new ReadResult<>(document, errors);
  }

  @Override
  public String write(Ebi61InvoiceType invoice) {
    String xml =
        newMarshaller()
            .setFormattedOutput(true)
            .setCharset(StandardCharsets.UTF_8)
            .getAsString(invoice);
    if (xml == null) {
      throw new IllegalStateException(
          "ebInterface 6.1 Dokument konnte nicht serialisiert werden."
              + " (ebInterface 6.1 document could not be marshalled.)");
    }
    return xml;
  }
}
