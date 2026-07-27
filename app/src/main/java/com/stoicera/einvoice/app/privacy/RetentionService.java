package com.stoicera.einvoice.app.privacy;

import com.stoicera.einvoice.app.persistence.AuditEventRepository;
import com.stoicera.einvoice.app.persistence.ReportRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <h2>One instance purges, however many are running</h2>
 *
 * <p>The application is stateless and horizontally scalable, so every instance runs this schedule
 * and every instance would fire the same purge at the same minute against the same database. The
 * deletes are idempotent, so that is <em>correct</em> and purely wasteful — N identical bulk
 * deletes over an indexed timestamp, N-1 of which find nothing, each holding a connection and
 * taking row locks the others then wait on. M5 recorded this honestly as "the retention job runs in
 * every instance … instance election belongs to M6"; this is that.
 *
 * <p>Election is a <strong>PostgreSQL transaction-scoped advisory lock</strong> ({@code
 * pg_try_advisory_xact_lock}), taken as the first statement of the purge transaction. It is the
 * boring choice on purpose:
 *
 * <ul>
 *   <li>It needs no new dependency (ShedLock is the usual answer and would be a library, a table
 *       and a migration for one job), no new table, and no configuration.
 *   <li>{@code try} rather than blocking: an instance that does not get the lock has nothing to do,
 *       so waiting for a purge someone else is already performing would only hold a connection.
 *   <li><strong>{@code _xact_} rather than the session-level {@code pg_try_advisory_lock}, and this
 *       is the part worth being careful about.</strong> A session lock must be released explicitly,
 *       and the release would go through {@code JdbcTemplate} — which, outside a transaction, hands
 *       out a <em>different pooled connection</em> than the one that took the lock. The unlock then
 *       silently fails and the lock survives on a connection sitting idle in the pool: the job is
 *       wedged until that connection is recycled, which may be never. The transaction-scoped lock
 *       has no release call to get wrong; the database drops it at commit or rollback, including
 *       when the instance is killed mid-purge.
 *   <li>The lock is keyed on a constant this application chose ({@link #PURGE_LOCK_KEY}), because
 *       advisory locks share one unnamed namespace per database.
 * </ul>
 *
 * <p>The transaction is opened <em>explicitly</em>, by a {@link TransactionTemplate} this service
 * owns, rather than by {@code @Transactional} on {@link #purge()}. The lock's scope IS the
 * transaction, so "is there a transaction?" is not a question this method may leave to how it was
 * called: through the bean proxy the annotation would apply, on a directly constructed instance it
 * would not, and the second case degrades to no election at all — silently, which is exactly the
 * failure mode this repository keeps finding in review.
 *
 * <p>The honest limit: this elects a purger <em>per database</em>, which is exactly the scope that
 * matters here, and it is not a general-purpose scheduler lock. A second scheduled job would want
 * its own key and this class is not the place to grow one.
 *
 * <p>{@link #purge()} is separated from the schedule so it can be called directly by a test and by
 * an operator; nothing about its behaviour depends on how it was triggered. The {@link Clock} is
 * injected for the same reason — a retention window is only testable if "now" can be chosen.
 */
@Service
public class RetentionService {

  private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

  /**
   * The advisory-lock key this job elects on.
   *
   * <p>PostgreSQL advisory locks live in one namespace per database and carry no name, so the
   * number <em>is</em> the identity: any other application sharing this database and picking the
   * same {@code bigint} would silently take turns with the retention purge. The value is the eight
   * ASCII bytes of {@code "einv_prg"}, so it is unlikely to collide with a round number someone
   * else picked, and it is named here rather than inlined so a second scheduled job cannot copy it
   * by reaching for the literal.
   */
  static final long PURGE_LOCK_KEY = 0x65696E765F707267L; // "einv_prg"

  /** What one purge removed. */
  public record Purged(long reports, long auditEvents) {}

  private final ReportRepository reports;
  private final AuditEventRepository auditEvents;
  private final Clock clock;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final int reportDays;
  private final int auditDays;

  public RetentionService(
      ReportRepository reports,
      AuditEventRepository auditEvents,
      Clock clock,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      @Value("${app.retention.report-days}") int reportDays,
      @Value("${app.retention.audit-days}") int auditDays) {
    this.reports = reports;
    this.auditEvents = auditEvents;
    this.clock = clock;
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.reportDays = reportDays;
    this.auditDays = auditDays;
  }

  /**
   * Deletes reports and audit events past their retention window, on the one instance that wins the
   * advisory lock.
   *
   * <p>One transaction for the lock and both tables. The two tables are independent, so a partial
   * purge would not be corrupting — but it would make the log line a lie about what was removed,
   * and there is no reason to accept that when the alternative costs nothing. Sharing the
   * transaction with the lock is what makes the lock cover the deletes at all.
   *
   * @return what this instance removed; {@code Purged(0, 0)} when another instance holds the lock,
   *     which is indistinguishable from "there was nothing to remove" and deliberately so — both
   *     mean "this instance deleted nothing"
   */
  @Scheduled(cron = "${app.retention.cron}")
  public Purged purge() {
    return transactions.execute(
        status -> {
          if (!tryAcquirePurgeLock()) {
            log.debug("Retention purge skipped: another instance holds the purge lock");
            return new Purged(0, 0);
          }
          return purgeExpiredRows();
        });
  }

  /**
   * Takes the transaction-scoped advisory lock without waiting; the database releases it when the
   * surrounding transaction ends, so there is no unlock call and none to get wrong.
   *
   * <p>Runs on the transaction's own connection because {@code JdbcTemplate} resolves through
   * {@code DataSourceUtils}, which returns the connection bound to the active transaction — the
   * same one the repository deletes below will use. That is the property the whole design rests on,
   * and it is why {@link #purge()} opens the transaction itself rather than hoping for one.
   */
  private boolean tryAcquirePurgeLock() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, PURGE_LOCK_KEY));
  }

  private Purged purgeExpiredRows() {
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
