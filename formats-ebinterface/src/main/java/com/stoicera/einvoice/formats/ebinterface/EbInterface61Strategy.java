package com.stoicera.einvoice.formats.ebinterface;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.list.ErrorList;
import com.helger.ebinterface.EEbInterfaceVersion;
import com.helger.ebinterface.EbInterface61Marshaller;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.helger.jaxb.GenericJAXBMarshaller;
import com.stoicera.einvoice.formats.api.ReadResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.w3c.dom.Node;

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
    return read(marshaller -> marshaller.read(xml));
  }

  @Override
  public ReadResult<Ebi61InvoiceType> read(Node node) {
    return read(marshaller -> marshaller.read(node));
  }

  /**
   * Shared lenient-read body: install a fresh, schema-off marshaller with error collection, run the
   * caller's chosen source overload, and turn the collected diagnostics into a {@link ReadResult}.
   *
   * <p>Without schema validation, JAXB still returns a (near-empty) tree for well-formed input
   * whose root element is not ebInterface 6.1, but reports the mismatch as an error. Any collected
   * error-level diagnostic therefore fails the read so callers get a {@code null} document, not a
   * hollow one — identical semantics whether the source was raw bytes or an already-parsed DOM.
   */
  private static ReadResult<Ebi61InvoiceType> read(
      Function<GenericJAXBMarshaller<Ebi61InvoiceType>, Ebi61InvoiceType> source) {
    ErrorList errorList = new ErrorList();
    Ebi61InvoiceType document = source.apply(newMarshaller().setCollectErrors(errorList));

    List<String> errors = new ArrayList<>();
    for (IError error : errorList) {
      errors.add(error.getAsStringLocaleIndepdent());
    }

    if (errorList.containsAtLeastOneError()) {
      document = null;
    }

    return new ReadResult<>(document, errors);
  }

  @Override
  public String write(Ebi61InvoiceType invoice) {
    return requireMarshalled(
        newMarshaller()
            .setFormattedOutput(true)
            .setCharset(StandardCharsets.UTF_8)
            .getAsString(invoice));
  }

  /**
   * Turns {@code getAsString}'s "null means it failed" convention into an exception.
   *
   * <p>{@link com.helger.jaxb.IJAXBWriter#getAsString} is declared nullable and answers {@code
   * null} — it does not throw — when the underlying marshal fails. Without this guard {@link
   * #write(Ebi61InvoiceType)} would hand its caller a {@code null} String and the failure would
   * surface far from its cause.
   *
   * <p>Package-private and static so the policy can be pinned by a direct unit test (the same shape
   * {@code XsdValidationStage}'s helpers use). Feeding the strategy a tree that actually makes the
   * marshaller fail is not a viable test: ph-ebinterface's writer escapes or drops even characters
   * that are unrepresentable in XML 1.0 (a raw {@code U+0000}, a lone surrogate), so no input we
   * can legitimately build reaches the null return. The guard stays because the library's contract
   * permits it, not because a fixture can provoke it.
   */
  static String requireMarshalled(String xml) {
    if (xml == null) {
      throw new IllegalStateException("ebInterface 6.1 document could not be marshalled.");
    }
    return xml;
  }
}
