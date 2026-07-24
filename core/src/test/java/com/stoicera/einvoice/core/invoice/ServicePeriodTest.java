package com.stoicera.einvoice.core.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stoicera.einvoice.core.InvariantViolationException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ServicePeriodTest {

  @Test
  void fromBeforeToIsAccepted() {
    ServicePeriod period = new ServicePeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    assertThat(period.from()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(period.to()).isEqualTo(LocalDate.of(2026, 1, 31));
  }

  @Test
  void fromEqualToToIsAccepted() {
    LocalDate day = LocalDate.of(2026, 7, 23);
    ServicePeriod period = new ServicePeriod(day, day);
    assertThat(period.from()).isEqualTo(day);
    assertThat(period.to()).isEqualTo(day);
  }

  @Test
  void fromAfterToIsRejected() {
    assertThatThrownBy(
            () -> new ServicePeriod(LocalDate.of(2026, 7, 23), LocalDate.of(2026, 7, 22)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("end date")
        .hasMessageContaining("start date");
  }

  @Test
  void nullFromIsRejected() {
    assertThatThrownBy(() -> new ServicePeriod(null, LocalDate.of(2026, 7, 23)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("start date");
  }

  @Test
  void nullToIsRejected() {
    assertThatThrownBy(() -> new ServicePeriod(LocalDate.of(2026, 7, 23), null))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("end date");
  }
}
