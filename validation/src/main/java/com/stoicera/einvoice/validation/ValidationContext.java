package com.stoicera.einvoice.validation;

import com.helger.ebinterface.EEbInterfaceVersion;
import com.helger.ebinterface.v61.Ebi61InvoiceType;
import com.stoicera.einvoice.formats.ebinterface.EbInterface61Strategy;
import com.stoicera.einvoice.validation.internal.SecureXml;
import java.util.Optional;
import org.w3c.dom.Document;

/**
 * Mutable per-run state shared by the pipeline stages.
 *
 * <p>It owns the raw upload bytes and memoizes the expensive derivations every later stage needs:
 * the securely parsed DOM (parsed at most once), the ebInterface version detected from the root
 * namespace (set by {@code FormatDetectionStage}), and the leniently parsed ebInterface 6.1
 * document tree (read at most once, used by the Schematron and business-rule stages). Derivations
 * are lazy and cached, so a stage can ask for the DOM without knowing whether an earlier stage
 * already parsed it.
 */
public final class ValidationContext {

  private final byte[] xml;

  private boolean domParsed;
  private Document dom;

  private EEbInterfaceVersion detectedVersion;

  private boolean invoiceParsed;
  private Ebi61InvoiceType invoice;

  public ValidationContext(byte[] xml) {
    this.xml = xml.clone();
  }

  /** The raw upload bytes (defensive copy). */
  public byte[] xml() {
    return xml.clone();
  }

  /** The securely parsed DOM, parsed at most once; empty when the bytes are not well-formed XML. */
  public Optional<Document> dom() {
    if (!domParsed) {
      dom = SecureXml.parse(xml).orElse(null);
      domParsed = true;
    }
    return Optional.ofNullable(dom);
  }

  /** The ebInterface version detected from the root namespace, once a stage has set it. */
  public Optional<EEbInterfaceVersion> detectedVersion() {
    return Optional.ofNullable(detectedVersion);
  }

  /** Records the ebInterface version detected for this run. */
  public void setDetectedVersion(EEbInterfaceVersion version) {
    this.detectedVersion = version;
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
