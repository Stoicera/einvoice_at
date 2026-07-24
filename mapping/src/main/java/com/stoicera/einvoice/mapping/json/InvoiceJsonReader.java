package com.stoicera.einvoice.mapping.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.stoicera.einvoice.core.invoice.Invoice;
import com.stoicera.einvoice.core.invoice.InvoiceLine;
import com.stoicera.einvoice.core.invoice.InvoiceTypeCode;
import com.stoicera.einvoice.core.party.Address;
import com.stoicera.einvoice.core.party.Party;
import com.stoicera.einvoice.core.payment.Iban;
import com.stoicera.einvoice.core.payment.PaymentMeans;
import com.stoicera.einvoice.core.tax.VatCategory;
import com.stoicera.einvoice.core.tax.VatExemptionReason;
import com.stoicera.einvoice.core.tax.VatRate;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Strict JSON reader for the canonical-invoice document shape documented in {@code
 * samples/README.md} (canonical example: {@code samples/invoice-b2g-sample.json}).
 *
 * <h2>Boundary strictness</h2>
 *
 * <p>This is an untrusted-input boundary, not an internal API, so it is deliberately stricter than
 * Jackson's defaults: unknown properties are rejected ({@code FAIL_ON_UNKNOWN_PROPERTIES}), and
 * money/quantity fields ({@code quantity}, {@code unitPrice}, {@code vatPercent}) must be JSON
 * strings — a numeric JSON node is rejected rather than silently stringified. (Jackson's own {@code
 * USE_BIG_DECIMAL_FOR_FLOATS} setting is irrelevant here: these fields are typed {@code String} on
 * the DTOs below, never {@code BigDecimal}/{@code double}, so no float ever reaches Jackson's
 * number handling; the rejection instead comes from disabling Jackson's number-to-string coercion
 * via {@code CoercionConfig}.)
 *
 * <h2>Two voices, two exception types</h2>
 *
 * <p>A JSON-shape problem — malformed syntax, an unknown property, a numeric node where a string is
 * required, an unmapped enum value, an unparsable date/currency/amount — is this reader's concern
 * and becomes {@link InvoiceJsonException}. A well-formed document that describes an invoice
 * violating a domain invariant (a blank invoice number, a checksum-invalid IBAN, a missing seller)
 * is {@code core}'s concern: {@link Invoice.Builder#build()} throws {@code
 * InvariantViolationException} directly, and this reader lets it propagate
 * <strong>untouched</strong> — the domain's voice is never rewrapped.
 *
 * <h2>Field mapping (JSON → canonical)</h2>
 *
 * <table>
 *   <caption>Canonical-invoice JSON to canonical model field mapping</caption>
 *   <tr><th>JSON</th><th>Canonical</th><th>Notes</th></tr>
 *   <tr><td>{@code invoiceNumber}</td><td>{@code Invoice.invoiceNumber}</td><td>copied verbatim.</td></tr>
 *   <tr><td>{@code type}</td><td>{@code Invoice.type}</td>
 *       <td>{@code "INVOICE"} → {@link InvoiceTypeCode#COMMERCIAL_INVOICE}; {@code "CREDIT_NOTE"} →
 *       {@link InvoiceTypeCode#CREDIT_NOTE}; any other value is a JSON-shape error.</td></tr>
 *   <tr><td>{@code issueDate}, {@code dueDate}</td><td>{@code Invoice.issueDate/dueDate}</td>
 *       <td>ISO-8601 ({@code yyyy-MM-dd}) via {@link LocalDate#parse(CharSequence)};
 *       {@code dueDate} optional.</td></tr>
 *   <tr><td>{@code currency}</td><td>{@code Invoice.currency}</td>
 *       <td>ISO 4217 code via {@link Currency#getInstance(String)}.</td></tr>
 *   <tr><td>{@code orderReference}, {@code supplierNumber}</td>
 *       <td>{@code Invoice.orderReference/supplierNumber}</td><td>optional, copied verbatim.</td></tr>
 *   <tr><td>{@code seller}, {@code buyer}</td><td>{@code Invoice.seller/buyer}</td>
 *       <td>{@code name}/{@code vatId}/{@code address} → {@link Party}; nested {@code address.*} →
 *       {@link Address}.</td></tr>
 *   <tr><td>{@code lines[]}</td><td>{@code Invoice.lines}</td>
 *       <td>{@code quantity}/{@code unitPrice} (JSON strings) → {@link BigDecimal};
 *       {@code vatCategory}+{@code vatPercent} → {@code new VatRate(VatCategory.<cat>, percent)}.
 *       </td></tr>
 *   <tr><td>{@code paymentMeans}</td><td>{@code Invoice.paymentMeans}</td>
 *       <td>{@code iban} → {@link Iban} (checksum-validated by {@code core}); {@code bic} optional;
 *       the whole object is omitted when absent.</td></tr>
 *   <tr><td>{@code paymentTerms}</td><td>{@code Invoice.paymentTerms}</td><td>optional.</td></tr>
 *   <tr><td>{@code exemptionReasons[]}</td><td>{@code Invoice.Builder#exemptionReason}</td>
 *       <td>{@code category} → {@link VatCategory}; {@code code}/{@code text} →
 *       {@link VatExemptionReason}; optional, one entry wired per category.</td></tr>
 * </table>
 *
 * <p>Stateless and safe to share across threads (mirrors {@code InvoiceToEbInterface61Mapper}).
 */
public final class InvoiceJsonReader {

  private static final ObjectMapper MAPPER = buildMapper();

  private static ObjectMapper buildMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    // Money/quantity fields are JSON strings by contract (samples/README.md); Jackson's default
    // behavior silently stringifies a numeric node (e.g. "unitPrice": 45.50 -> "45.50") unless
    // number-to-string coercion is disabled here, which is what actually rejects it.
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    return mapper;
  }

  /**
   * Parses {@code json} and builds a validated {@link Invoice}.
   *
   * @param json the canonical-invoice JSON document; not closed by this method
   * @return the validated invoice
   * @throws InvoiceJsonException the JSON does not conform to the documented shape
   * @throws com.stoicera.einvoice.core.InvariantViolationException the JSON is well-formed but the
   *     invoice it describes violates a domain invariant (propagated untouched)
   */
  public Invoice read(InputStream json) throws InvoiceJsonException {
    return toInvoice(parse(json));
  }

  private InvoiceDto parse(InputStream json) {
    try {
      InvoiceDto dto = MAPPER.readValue(json, InvoiceDto.class);
      if (dto == null) {
        throw new InvoiceJsonException("JSON body must not be null");
      }
      return dto;
    } catch (UnrecognizedPropertyException e) {
      throw new InvoiceJsonException(
          "Unknown property '" + e.getPropertyName() + "' at " + fieldPath(e), e);
    } catch (JsonMappingException e) {
      throw new InvoiceJsonException("Invalid value for field '" + fieldPath(e) + "'", e);
    } catch (JsonProcessingException e) {
      throw new InvoiceJsonException("Malformed JSON input", e);
    } catch (IOException e) {
      throw new InvoiceJsonException("Failed to read JSON input", e);
    }
  }

  /**
   * Joins a Jackson error path into a dotted, JSON pointer-ish string, e.g. {@code
   * lines[0].unitPrice}.
   */
  private static String fieldPath(JsonMappingException e) {
    StringBuilder path = new StringBuilder();
    for (JsonMappingException.Reference ref : e.getPath()) {
      if (ref.getFieldName() != null) {
        if (path.length() > 0) {
          path.append('.');
        }
        path.append(ref.getFieldName());
      } else {
        path.append('[').append(ref.getIndex()).append(']');
      }
    }
    return path.length() == 0 ? "<root>" : path.toString();
  }

  private Invoice toInvoice(InvoiceDto dto) {
    Invoice.Builder builder =
        Invoice.builder()
            .invoiceNumber(dto.invoiceNumber())
            .type(toTypeCode(dto.type()))
            .issueDate(toDate(dto.issueDate(), "issueDate"))
            .dueDate(toDate(dto.dueDate(), "dueDate"))
            .currency(toCurrency(dto.currency()))
            .orderReference(dto.orderReference())
            .supplierNumber(dto.supplierNumber())
            .seller(toParty(dto.seller()))
            .buyer(toParty(dto.buyer()))
            .paymentTerms(dto.paymentTerms());

    for (InvoiceLine line : toLines(dto.lines())) {
      builder.addLine(line);
    }
    if (dto.paymentMeans() != null) {
      builder.paymentMeans(toPaymentMeans(dto.paymentMeans()));
    }
    if (dto.exemptionReasons() != null) {
      for (ExemptionReasonDto reason : dto.exemptionReasons()) {
        if (reason == null) {
          throw new InvoiceJsonException("exemptionReasons[] entries must not be null");
        }
        builder.exemptionReason(
            toVatCategory(reason.category(), "exemptionReasons[].category"),
            new VatExemptionReason(reason.code(), reason.text()));
      }
    }
    return builder.build();
  }

  private List<InvoiceLine> toLines(List<LineDto> lines) {
    if (lines == null) {
      return List.of();
    }
    List<InvoiceLine> result = new ArrayList<>(lines.size());
    for (LineDto line : lines) {
      if (line == null) {
        throw new InvoiceJsonException("lines[] entries must not be null");
      }
      result.add(toLine(line));
    }
    return result;
  }

  private InvoiceLine toLine(LineDto line) {
    VatRate rate =
        new VatRate(
            toVatCategory(line.vatCategory(), "lines[].vatCategory"),
            toAmount(line.vatPercent(), "lines[].vatPercent"));
    return new InvoiceLine(
        line.id(),
        line.description(),
        toAmount(line.quantity(), "lines[].quantity"),
        line.unitCode(),
        toAmount(line.unitPrice(), "lines[].unitPrice"),
        rate);
  }

  private Party toParty(PartyDto party) {
    if (party == null) {
      return null;
    }
    return new Party(party.name(), toAddress(party.address()), party.vatId());
  }

  private Address toAddress(AddressDto address) {
    if (address == null) {
      return null;
    }
    return new Address(
        address.street(), address.city(), address.postalCode(), address.countryCode());
  }

  private PaymentMeans toPaymentMeans(PaymentDto payment) {
    return new PaymentMeans(new Iban(payment.iban()), payment.bic());
  }

  private InvoiceTypeCode toTypeCode(String raw) {
    if (raw == null) {
      return null;
    }
    return switch (raw) {
      case "INVOICE" -> InvoiceTypeCode.COMMERCIAL_INVOICE;
      case "CREDIT_NOTE" -> InvoiceTypeCode.CREDIT_NOTE;
      default ->
          throw new InvoiceJsonException(
              "Field 'type' has an unsupported value; expected INVOICE or CREDIT_NOTE");
    };
  }

  private VatCategory toVatCategory(String raw, String field) {
    if (raw == null) {
      return null;
    }
    try {
      return VatCategory.valueOf(raw);
    } catch (IllegalArgumentException e) {
      throw new InvoiceJsonException(
          "Field '" + field + "' has an unsupported VAT category value", e);
    }
  }

  private Currency toCurrency(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return Currency.getInstance(raw);
    } catch (IllegalArgumentException e) {
      throw new InvoiceJsonException("Field 'currency' is not a valid ISO 4217 currency code", e);
    }
  }

  private LocalDate toDate(String raw, String field) {
    if (raw == null) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new InvoiceJsonException("Field '" + field + "' is not a valid ISO-8601 date", e);
    }
  }

  private BigDecimal toAmount(String raw, String field) {
    if (raw == null) {
      return null;
    }
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      throw new InvoiceJsonException("Field '" + field + "' is not a valid decimal string", e);
    }
  }

  // --- DTOs (private: the JSON shape is an implementation detail behind read()) ----------------

  private record InvoiceDto(
      String invoiceNumber,
      String type,
      String issueDate,
      String dueDate,
      String currency,
      String orderReference,
      String supplierNumber,
      PartyDto seller,
      PartyDto buyer,
      List<LineDto> lines,
      PaymentDto paymentMeans,
      String paymentTerms,
      List<ExemptionReasonDto> exemptionReasons) {}

  private record PartyDto(String name, String vatId, AddressDto address) {}

  private record AddressDto(String street, String city, String postalCode, String countryCode) {}

  private record LineDto(
      String id,
      String description,
      String quantity,
      String unitCode,
      String unitPrice,
      String vatCategory,
      String vatPercent) {}

  private record PaymentDto(String iban, String bic) {}

  private record ExemptionReasonDto(String category, String code, String text) {}
}
