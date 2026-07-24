package com.stoicera.einvoice.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The identity contract every persistence entity shares: equality is the application-assigned
 * {@code id} and nothing else.
 *
 * <p>Not boilerplate worth skipping. JPA entities are the classic place where {@code equals} goes
 * wrong — a database-generated id is null until flush, so an entity put in a {@code HashSet} before
 * then changes its own hash code and is lost inside the collection. This codebase sidesteps that by
 * assigning the {@link UUID} in the constructor (ADR-0005: "no DB sequences"), which makes
 * id-equality safe from the moment of construction. That is a property of the design, so it is
 * asserted rather than assumed: a later switch to a generated id would break these tests, which is
 * exactly the warning that change deserves.
 *
 * <p>A plain unit test — entities are ordinary objects until a persistence context touches them, so
 * no container is involved.
 */
class EntityIdentityTest {

  /** One factory per entity: each call must produce a fresh, distinctly-identified instance. */
  private static final List<Supplier<Object>> FACTORIES =
      List.of(
          () -> new TenantEntity("kc-sub-" + UUID.randomUUID(), "Display Name"),
          () ->
              new InvoiceEntity(
                  UUID.randomUUID(),
                  "RE-1",
                  "380",
                  LocalDate.of(2026, 7, 24),
                  "EUR",
                  new BigDecimal("100.00"),
                  "Seller",
                  "Buyer",
                  "{}"),
          () -> new ReportEntity(UUID.randomUUID(), null, "ebinterface-6.1", "at-b2g", true, "[]"),
          () -> new ApiKeyEntity(UUID.randomUUID(), "name", "hash", "eiv_abcd"),
          () -> new AuditEventEntity(UUID.randomUUID(), "INVOICE_CREATED", null));

  @Test
  void everyEntityEqualsItselfAndNothingElse() {
    for (Supplier<Object> factory : FACTORIES) {
      Object entity = factory.get();
      Object other = factory.get();

      assertThat(entity).isEqualTo(entity).isNotEqualTo(other).isNotEqualTo(null);
      // A different entity type is never equal, even though both are id-keyed.
      assertThat(entity).isNotEqualTo("not an entity");
    }
  }

  @Test
  void hashCodeIsStableAndUsableInACollectionFromConstructionOnwards() {
    for (Supplier<Object> factory : FACTORIES) {
      Object entity = factory.get();
      int atConstruction = entity.hashCode();

      // Stable across calls, and — the property that actually matters — the instance is still
      // findable after being put into a hash-based collection, with no flush in between.
      assertThat(entity.hashCode()).isEqualTo(atConstruction);
      HashSet<Object> set = new HashSet<>();
      set.add(entity);
      assertThat(set).contains(entity);
      assertThat(set).doesNotContain(factory.get());
    }
  }

  @Test
  void twoEntitiesOfTheSameTypeNeverCollideOnTheirGeneratedIds() {
    for (Supplier<Object> factory : FACTORIES) {
      HashSet<Object> distinct = new HashSet<>();
      for (int i = 0; i < 50; i++) {
        distinct.add(factory.get());
      }
      assertThat(distinct).hasSize(50);
    }
  }

  @Test
  void aRevokedApiKeyReportsItselfRevokedAndKeepsItsFirstRevocationInstant() {
    ApiKeyEntity key = new ApiKeyEntity(UUID.randomUUID(), "name", "hash", "eiv_abcd");
    assertThat(key.isRevoked()).isFalse();
    assertThat(key.getRevokedAt()).isNull();

    java.time.Instant first = java.time.Instant.parse("2026-07-24T10:00:00Z");
    key.revoke(first);
    assertThat(key.isRevoked()).isTrue();
    assertThat(key.getRevokedAt()).isEqualTo(first);

    // Revoking again must not move the timestamp: the audit trail records when a key stopped
    // working, and a second DELETE is not a second revocation.
    key.revoke(java.time.Instant.parse("2026-07-25T10:00:00Z"));
    assertThat(key.getRevokedAt()).isEqualTo(first);
  }
}
