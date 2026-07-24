package com.stoicera.einvoice.validation.stage;

import static org.assertj.core.api.Assertions.assertThat;

import com.helger.ebinterface.EEbInterfaceVersion;
import com.stoicera.einvoice.core.validation.Finding;
import com.stoicera.einvoice.core.validation.Severity;
import com.stoicera.einvoice.validation.TestDocuments;
import com.stoicera.einvoice.validation.ValidationContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormatDetectionStageTest {

  private final FormatDetectionStage stage = new FormatDetectionStage();

  @Test
  void detectsEbInterface61RecordsVersionAndReportsNothing() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.validEbInterface61()));

    List<Finding> findings = stage.apply(ctx);

    assertThat(findings).isEmpty();
    assertThat(ctx.detectedVersion()).contains(EEbInterfaceVersion.V61);
  }

  @Test
  void unknownNamespaceYieldsFormat01() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.unknownNamespace()));

    List<Finding> findings = stage.apply(ctx);

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("FORMAT-01");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(ctx.detectedVersion()).isEmpty();
  }

  @Test
  void ebInterface60YieldsFormat02NamingBothVersions() {
    ValidationContext ctx =
        new ValidationContext(TestDocuments.bytes(TestDocuments.ebInterface60()));

    List<Finding> findings = stage.apply(ctx);

    assertThat(findings).hasSize(1);
    Finding finding = findings.get(0);
    assertThat(finding.ruleId()).isEqualTo("FORMAT-02");
    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
    assertThat(finding.messageDe()).contains("6.0").contains("6.1");
    assertThat(ctx.detectedVersion()).isEmpty();
  }
}
