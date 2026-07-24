package com.stoicera.einvoice.formats.ebinterface;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadResultTest {

  @Test
  void isSuccessWhenDocumentPresent() {
    ReadResult<String> result = new ReadResult<>("document", List.of());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.document()).isEqualTo("document");
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void successMayStillCarryDiagnostics() {
    // A successful read can carry non-error diagnostics (warnings/infos); success is keyed on
    // isSuccess(), never on errors being empty. This pins the honest contract of the record.
    ReadResult<String> result = new ReadResult<>("document", List.of("info: schema not enforced"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.errors()).isNotEmpty();
  }

  @Test
  void isFailureWhenDocumentNull() {
    ReadResult<String> result = new ReadResult<>(null, List.of("boom"));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.document()).isNull();
    assertThat(result.errors()).containsExactly("boom");
  }

  @Test
  void errorsAreDefensivelyCopiedAndImmutable() {
    List<String> mutable = new ArrayList<>();
    mutable.add("one");

    ReadResult<String> result = new ReadResult<>(null, mutable);
    mutable.add("two"); // must not leak into the result

    assertThat(result.errors()).containsExactly("one");
    assertThatThrownBy(() -> result.errors().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
