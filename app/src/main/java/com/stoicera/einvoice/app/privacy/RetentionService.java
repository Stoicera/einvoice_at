package com.stoicera.einvoice.app.privacy;

import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage-limitation (GDPR Art. 5(1)(e)): expires data nobody needs any more, on a schedule.
 *
 * <p>The second half of the gap {@code docs/privacy.md} §4 named: "Aufbewahrungsfristen /
 * automatische Löschung: noch nicht implementiert. Prüfberichte und Audit-Einträge bleiben derzeit
 * unbegrenzt liegen."
 *
 * <h2>Invoices are never expired, and that is a legal requirement, not a default</h2>
 *
 * <p>It would be easy — and symmetrical, and wrong — to expire invoices alongside reports. An
 * Austrian business must retain its invoices for <strong>seven years</strong> (§ 132 BAO). A
 * platform that quietly deleted them after a year would not be protecting the user's privacy; it
 * would be destroying records they are required to hold, on their behalf, without being asked. So
 * this job touches exactly two tables:
 *
 * <ul>
 *   <li><strong>reports</strong> — a validation verdict is operational output. It can be reproduced
 *       by re-validating, and after a year nobody consults it.
 *   <li><strong>audit events</strong> — the security trail. Useful for months, not forever, and it
 *       is the table that grows fastest.
 * </ul>
 *
 * <p>Erasure on request is a different operation with different rules and lives in {@link
 * TenantErasureService}: that one <em>does</em> delete invoices, because the person asked and their
 * retention obligation is theirs to discharge elsewhere.
 *
 * <h2>Configuration, and how to switch it off</h2>
 *
 * <p>Windows are set in days by {@code app.retention.report-days} and {@code
 * app.retention.audit-days}. <strong>Zero or negative means keep forever</strong> — that is the off
 * switch, and it is one mechanism rather than a second {@code enabled} flag that could disagree
 * with the windows. The schedule itself is {@code app.retention.cron}.
 *
 * <p>{@link #purge()} is separated from the schedule so it can be called directly by a test and by
 * an operator; nothing about its behaviour depends on how it was triggered. The {@link Clock} is
 * injected for the same reason — a retention window is only testable if "now" can be chosen.
 */
@Service
public class RetentionService {

  private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

  /** What one purge removed. */
  public record Purged(long reports, long auditEvents) {}

  private final ReportRepository reports;
  private final AuditEventRepository auditEvents;
  private final Clock clock;
  private final int reportDays;
  private final int auditDays;

  public RetentionService(
      ReportRepository reports,
      AuditEventRepository auditEvents,
      Clock clock,
      @Value("${app.retention.report-days}") int reportDays,
      @Value("${app.retention.audit-days}") int auditDays) {
    this.reports = reports;
    this.auditEvents = auditEvents;
    this.clock = clock;
    this.reportDays = reportDays;
    this.auditDays = auditDays;
  }

  /**
   * Deletes reports and audit events past their retention window.
   *
   * <p>One transaction for both tables. They are independent, so a partial purge would not be
   * corrupting — but it would make the log line a lie about what was removed, and there is no
   * reason to accept that when the alternative costs nothing.
   */
  @Scheduled(cron = "${app.retention.cron}")
  @Transactional
  public Purged purge() {
    Instant now = clock.instant();
    long purgedReports =
        reportDays > 0 ? reports.deleteByCreatedAtBefore(cutoff(now, reportDays)) : 0;
    long purgedAuditEvents =
        auditDays > 0 ? auditEvents.deleteByOccurredAtBefore(cutoff(now, auditDays)) : 0;

    if (purgedReports > 0 || purgedAuditEvents > 0) {
      log.info(
          "Retention purge removed {} reports older than {} days and {} audit events older than {}"
              + " days",
          purgedReports,
          reportDays,
          purgedAuditEvents,
          auditDays);
    }
    return new Purged(purgedReports, purgedAuditEvents);
  }

  private static Instant cutoff(Instant now, int days) {
    return now.minus(Duration.ofDays(days));
  }
}
