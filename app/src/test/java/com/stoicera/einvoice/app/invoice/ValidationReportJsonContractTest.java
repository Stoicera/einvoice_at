package com.stoicera.einvoice.app.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.core.validation.ValidationReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the JSON serialization of {@code core}'s {@link ValidationReport} / {@link Finding} / {@link
 * Severity}. From M3 this shape is public API contract (it is the {@code report} of {@code POST
 * /api/v1/invoices} and, later, the {@code POST /api/v1/validate} body), so a change to any field
 * name, the {@code valid} projection, the enum spelling, or the null handling must break this test
 * on purpose.
 *
 * <p>The mapper is a default Jackson 3 {@link JsonMapper} — the same engine Spring MVC serializes
 * responses with in Boot 4. {@code ValidationReport}/{@code Finding} carry no dates, {@code
 * Optional}s or naming-sensitive fields, so this default output is byte-for-byte what the wire
 * produces; the end-to-end HTTP integration test re-checks the same fields over a real response.
 */
class ValidationReportJsonContractTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  @SuppressWarnings("unchecked")
  void reportWithFindingsSerializesToTheContractShape() {
    ValidationReport report =
        new ValidationReport(
            "ebinterface-6.1",
            "at-b2g",
            List.of(
                Finding.of(
                    Severity.ERROR,
                    "AT-B2G-01",
                    null,
                    "Die Auftragsreferenz fehlt.",
                    "The order reference is missing."),
                new Finding(
                    Severity.WARN, "AT-B2G-99", "/Invoice/Foo", "de", "en", "AI-Erklärung")));

    Map<String, Object> root = mapper.readValue(mapper.writeValueAsString(report), Map.class);

    // Top level: exactly these four fields, with `valid` projected from ValidationReport.isValid().
    assertThat(root).containsOnlyKeys("sourceFormat", "profile", "valid", "findings");
    assertThat(root.get("sourceFormat")).isEqualTo("ebinterface-6.1");
    assertThat(root.get("profile")).isEqualTo("at-b2g");
    assertThat(root.get("valid")).isEqualTo(false); // an ERROR finding is present

    List<Map<String, Object>> findings = (List<Map<String, Object>>) root.get("findings");
    assertThat(findings).hasSize(2);

    Map<String, Object> first = findings.get(0);
    assertThat(first)
        .containsOnlyKeys(
            "severity", "ruleId", "location", "messageDe", "messageEn", "aiExplanation");
    assertThat(first.get("severity")).isEqualTo("ERROR"); // enum by name
    assertThat(first.get("ruleId")).isEqualTo("AT-B2G-01");
    assertThat(first.get("location")).isNull(); // absent optional field serialized as JSON null
    assertThat(first.get("messageDe")).isEqualTo("Die Auftragsreferenz fehlt.");
    assertThat(first.get("messageEn")).isEqualTo("The order reference is missing.");
    assertThat(first.get("aiExplanation")).isNull();

    Map<String, Object> second = findings.get(1);
    assertThat(second.get("severity")).isEqualTo("WARN");
    assertThat(second.get("location")).isEqualTo("/Invoice/Foo");
    assertThat(second.get("aiExplanation")).isEqualTo("AI-Erklärung");
  }

  @Test
  void cleanReportIsValidAndCarriesAnEmptyFindingsArray() {
    ValidationReport report = new ValidationReport("ebinterface-6.1", "at-b2g", List.of());

    Map<String, Object> root = mapper.readValue(mapper.writeValueAsString(report), Map.class);

    assertThat(root.get("valid")).isEqualTo(true);
    assertThat((List<?>) root.get("findings")).isEmpty();
  }

  @Test
  void severityEnumSerializesByName() {
    for (Severity severity : Severity.values()) {
      ValidationReport report =
          new ValidationReport(
              "ebinterface-6.1", "at-b2g", List.of(Finding.of(severity, "R", null, "de", "en")));
      String json = mapper.writeValueAsString(report);
      assertThat(json).contains("\"severity\":\"" + severity.name() + "\"");
    }
  }
}
