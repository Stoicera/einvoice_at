package com.stoicera.einvoice.app.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.validation.ValidationObserver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code app}-side half of the validation-stage tracing boundary (M6, ADR-0012).
 *
 * <p>Two things are worth pinning here and nothing else is. First, that the adapter is
 * <em>transparent</em>: it is a decorator around real validation work, so returning something other
 * than the stage's own value, or swallowing an exception, would corrupt a validation report in the
 * name of measuring it. Second, that the tag it produces is bounded — see {@link
 * #everyDeclaredStageNameIsABoundedTagValue()}.
 */
class MicrometerValidationObserverTest {

  private final TestObservationRegistry registry = TestObservationRegistry.create();
  private final MicrometerValidationObserver observer = new MicrometerValidationObserver(registry);

  @Test
  @DisplayName("returns the stage's own value, not a copy or a substitute")
  void isTransparent() {
    Object sentinel = new Object();

    assertThat(observer.observe(ValidationObserver.STAGE_XSD, () -> sentinel)).isSameAs(sentinel);
  }

  @Test
  @DisplayName("records one observation per stage, tagged with the stage name")
  void recordsTheStageAsATag() {
    observer.observe(ValidationObserver.STAGE_SCHEMATRON, () -> "findings");

    TestObservationRegistryAssert.assertThat(registry)
        .hasNumberOfObservationsEqualTo(1)
        .hasSingleObservationThat()
        .hasNameEqualTo(MicrometerValidationObserver.OBSERVATION_NAME)
        .hasContextualNameEqualTo("einvoice.validation.stage.schematron")
        .hasLowCardinalityKeyValue(
            MicrometerValidationObserver.STAGE_TAG, ValidationObserver.STAGE_SCHEMATRON)
        .doesNotHaveError();
  }

  @Test
  @DisplayName("a stage that throws is recorded as an error and the exception still propagates")
  void doesNotSwallowAStageFailure() {
    RuntimeException boom = new IllegalStateException("stage blew up");

    assertThatThrownBy(
            () ->
                observer.observe(
                    ValidationObserver.STAGE_PEPPOL,
                    () -> {
                      throw boom;
                    }))
        .isSameAs(boom);

    TestObservationRegistryAssert.assertThat(registry)
        .hasNumberOfObservationsEqualTo(1)
        .hasSingleObservationThat()
        .hasLowCardinalityKeyValue(
            MicrometerValidationObserver.STAGE_TAG, ValidationObserver.STAGE_PEPPOL)
        .hasError(boom);
  }

  /**
   * The guard against an unbounded tag. {@code stageName} reaches Micrometer as a low-cardinality
   * tag value, which is only safe because its values are the {@code STAGE_*} constants declared on
   * {@link ValidationObserver} — never a document value, a caller's input or a third party's
   * response (the M5 hostile review found exactly that defect in the AI cost metrics' {@code model}
   * tag).
   *
   * <p>Reflecting over the constants rather than listing them is deliberate: a stage added to
   * {@code validation} without a constant, or a constant added without a stage, changes what this
   * application publishes as a metric dimension, and this test is where that becomes visible. The
   * literal list below is the second half of the same guard — reflection alone would happily accept
   * a renamed or newly invented constant.
   */
  @Test
  @DisplayName("every stage name the validation module declares is a valid, bounded tag value")
  void everyDeclaredStageNameIsABoundedTagValue() throws Exception {
    List<String> declared = declaredStageNames();

    assertThat(declared)
        .as("ValidationObserver's STAGE_* constants — the complete tag vocabulary")
        .containsExactlyInAnyOrder(
            "parse", "format-detection", "xsd", "schematron", "business-rules", "peppol");

    declared.forEach(stage -> observer.observe(stage, () -> null));

    TestObservationRegistryAssert.assertThat(registry)
        .hasNumberOfObservationsEqualTo(declared.size())
        .hasHandledContextsThatSatisfy(
            contexts ->
                assertThat(contexts)
                    .allSatisfy(
                        context ->
                            assertThat(context.getName())
                                .isEqualTo(MicrometerValidationObserver.OBSERVATION_NAME))
                    .map(MicrometerValidationObserverTest::stageTag)
                    .containsExactlyElementsOf(declared));
  }

  private static String stageTag(Observation.Context context) {
    return context.getLowCardinalityKeyValue(MicrometerValidationObserver.STAGE_TAG).getValue();
  }

  private static List<String> declaredStageNames() throws Exception {
    List<String> names = new ArrayList<>();
    for (Field field : ValidationObserver.class.getDeclaredFields()) {
      if (field.getName().startsWith("STAGE_")
          && field.getType() == String.class
          && Modifier.isStatic(field.getModifiers())) {
        names.add((String) field.get(null));
      }
    }
    return names;
  }
}
