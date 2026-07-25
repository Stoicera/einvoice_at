package com.stoicera.einvoice.formats.ubl;

import com.helger.diagnostics.error.IError;
import com.helger.diagnostics.error.list.ErrorList;
import com.helger.jaxb.GenericJAXBMarshaller;
import com.stoicera.einvoice.formats.api.InvoiceFormatStrategy;
import com.stoicera.einvoice.formats.api.ReadResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.xml.namespace.QName;
import org.w3c.dom.Node;

/**
 * The read/write body both UBL billing strategies share, over ph-ubl's {@code UBL21Marshaller}.
 *
 * <p>UBL splits invoice and credit note into two root elements with two JAXB types, so there are
 * two strategies; everything they do apart from naming their marshaller and their document kind is
 * identical, and lives here rather than being copied twice.
 *
 * <p>The marshallers ship with the UBL 2.1 XSDs and validate against them by default. This adapter
 * deliberately turns that off ({@code setUseSchema(false)}): schema and Schematron validation are
 * the validation module's job, not this one — and for UBL that distinction matters more than for
 * ebInterface, because a document can be perfectly schema-valid UBL 2.1 and still violate dozens of
 * EN 16931 / Peppol BIS rules. Reads are therefore lenient: a structurally broken document yields a
 * failure with collected diagnostics rather than a thrown exception, and a technically-incomplete
 * tree can still be written.
 *
 * <p>JAXB marshallers are not thread-safe, so a fresh instance is created per call; subclasses hold
 * no mutable state and are safe to share.
 *
 * @param <T> the UBL JAXB document type ({@code InvoiceType} / {@code CreditNoteType})
 */
abstract class AbstractUbl21Strategy<T> implements InvoiceFormatStrategy<T> {

  private final UblDocumentKind kind;
  private final Supplier<? extends GenericJAXBMarshaller<T>> marshallers;

  AbstractUbl21Strategy(
      UblDocumentKind kind, Supplier<? extends GenericJAXBMarshaller<T>> marshallers) {
    this.kind = kind;
    this.marshallers = marshallers;
  }

  /** The UBL billing document kind this strategy reads and writes. */
  public final UblDocumentKind documentKind() {
    return kind;
  }

  @Override
  public final String namespaceUri() {
    return kind.namespaceUri();
  }

  @Override
  public final ReadResult<T> read(byte[] xml) {
    return read(UblRootElement.peek(xml), marshaller -> marshaller.read(xml));
  }

  @Override
  public final ReadResult<T> read(Node node) {
    return read(UblRootElement.of(node), marshaller -> marshaller.read(node));
  }

  /**
   * Shared lenient-read body: reject a root element that is not this strategy's, then install a
   * fresh, schema-off marshaller with error collection, run the caller's chosen source overload,
   * and turn the collected diagnostics into a {@link ReadResult}.
   *
   * <p>The root-element check is not belt-and-braces: JAXB unmarshals by declared type, so with
   * schema validation off a {@code ubl:CreditNote} handed to the invoice strategy would otherwise
   * be unmarshalled into an {@code InvoiceType} <em>without a single diagnostic</em> — the two
   * documents share the same {@code cbc:}/{@code cac:} child vocabulary. See {@link
   * UblRootElement}. An unreadable root (malformed bytes, no element) deliberately does not
   * short-circuit: the marshaller runs and reports the real parse diagnostics instead of this
   * method inventing a worse one.
   *
   * <p>Beyond that, any collected error-level diagnostic fails the read, so callers get a {@code
   * null} document rather than a hollow one — identical semantics whether the source was raw bytes
   * or an already-parsed DOM, and identical to the ebInterface adapter's.
   */
  private ReadResult<T> read(
      Optional<QName> rootElement, Function<GenericJAXBMarshaller<T>, T> source) {
    Optional<String> rootMismatch = rootElement.flatMap(this::describeIfNotMine);
    if (rootMismatch.isPresent()) {
      return new ReadResult<>(null, List.of(rootMismatch.get()));
    }

    ErrorList errorList = new ErrorList();
    T document = source.apply(newMarshaller().setCollectErrors(errorList));

    List<String> errors = new ArrayList<>();
    for (IError error : errorList) {
      errors.add(error.getAsStringLocaleIndepdent());
    }

    if (errorList.containsAtLeastOneError()) {
      document = null;
    }

    return new ReadResult<>(document, errors);
  }

  /**
   * A diagnostic describing {@code rootElement} when it is not the one this strategy reads, or
   * {@link Optional#empty()} when it is.
   */
  private Optional<String> describeIfNotMine(QName rootElement) {
    QName expected = new QName(kind.namespaceUri(), kind.rootElementLocalName());
    if (expected.equals(rootElement)) {
      return Optional.empty();
    }
    return Optional.of("Root element is %s, expected %s".formatted(rootElement, expected));
  }

  @Override
  public final String write(T document) {
    return requireMarshalled(
        newMarshaller()
            .setFormattedOutput(true)
            .setCharset(StandardCharsets.UTF_8)
            .getAsString(document));
  }

  /** A fresh marshaller with schema validation off — see the class Javadoc for why. */
  private GenericJAXBMarshaller<T> newMarshaller() {
    return marshallers.get().setUseSchema(false);
  }

  /**
   * Turns {@code getAsString}'s "null means it failed" convention into an exception.
   *
   * <p>{@link com.helger.jaxb.IJAXBWriter#getAsString} is declared nullable and answers {@code
   * null} — it does not throw — when the underlying marshal fails. Without this guard {@link
   * #write} would hand its caller a {@code null} String and the failure would surface far from its
   * cause.
   *
   * <p>Package-private and static so the policy can be pinned by a direct unit test, exactly as
   * {@code EbInterface61Strategy.requireMarshalled} is: no legitimately constructible tree makes
   * ph-ubl's writer fail, so the guard stays because the library's contract permits the null, not
   * because a fixture can provoke it.
   */
  static String requireMarshalled(String xml) {
    if (xml == null) {
      throw new IllegalStateException("UBL 2.1 document could not be marshalled.");
    }
    return xml;
  }
}
