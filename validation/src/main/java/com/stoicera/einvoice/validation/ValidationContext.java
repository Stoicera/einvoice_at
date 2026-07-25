package com.stoicera.einvoice.validation;

import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.validation.internal.SecureXml;
import java.util.Optional;
import org.w3c.dom.Document;

/**
 * Mutable per-run state shared by the pipeline stages.
 *
 * <p>It holds a private copy of the raw upload bytes and memoizes the two expensive derivations the
 * stages share: the securely parsed DOM (parsed at most once, consulted by every stage) and the
 * leniently parsed ebInterface 6.1 document tree (read at most once, used by the Schematron and
 * business-rule stages). Both derivations are lazy and cached, so a stage can ask for the DOM or
 * the parsed invoice without knowing whether an earlier stage already produced it.
 */
public final class ValidationContext {

  private final byte[] xml;

  private boolean domParsed;
  private Document dom;

  private boolean invoiceParsed;
  private Ebi61InvoiceType invoice;

  private DocumentFormat format = DocumentFormat.UNKNOWN;

  public ValidationContext(byte[] xml) {
    this.xml = xml.clone();
  }

  /**
   * The format the detection stage identified, or {@link DocumentFormat#UNKNOWN} until it has run.
   *
   * <p>Unlike the two memoized derivations below this is not lazily computed on demand: the format
   * is <em>decided</em> by one stage and then <em>read</em> by everything downstream — the facade
   * that picks the pipeline, and the Peppol stage that picks its rule set. Deriving it twice, in
   * two places, is exactly the drift this field exists to prevent.
   */
  public DocumentFormat format() {
    return format;
  }

  /** Records the detected format; called by the format-detection stage and nothing else. */
  public void format(DocumentFormat format) {
    this.format = format;
  }

  /** The securely parsed DOM, parsed at most once; empty when the bytes are not well-formed XML. */
  public Optional<Document> dom() {
    if (!domParsed) {
      dom = SecureXml.parse(xml).orElse(null);
      domParsed = true;
    }
    return Optional.ofNullable(dom);
  }

  /**
   * The leniently parsed ebInterface 6.1 document, read at most once; empty when the bytes do not
   * parse into a usable 6.1 tree. Used by the Schematron and business-rule stages.
   *
   * <p>The tree is unmarshalled from the already-hardened {@link #dom()} rather than re-read from
   * the raw bytes, so the untrusted input is parsed exactly once, through {@code SecureXml}'s
   * XXE-safe {@code DocumentBuilder}. No usable DOM (malformed or forbidden input) therefore means
   * no invoice.
   */
  public Optional<Ebi61InvoiceType> ebiInvoice() {
    if (!invoiceParsed) {
      invoice =
          dom().map(document -> new EbInterface61Strategy().read(document).document()).orElse(null);
      invoiceParsed = true;
    }
    return Optional.ofNullable(invoice);
  }
}
