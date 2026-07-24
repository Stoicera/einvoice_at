package com.stoicera.einvoice.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import com.stoicera.einvoice.app.persistence.TenantEntity;
import com.stoicera.einvoice.app.persistence.TenantRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tenant auto-provisioning against the real database. The first authenticated request for a subject
 * creates its tenant row; every later request reuses it. The concurrency test drives the
 * unique-constraint race the service is built to survive.
 *
 * <p>Uses only Postgres (no Keycloak): it targets {@link TenantProvisioningService} directly with
 * synthetic subjects, which is deterministic and exercises exactly the create-once / reuse / race
 * logic. The JWT wiring (a real login triggering provisioning through the API-key controller) is
 * covered end to end in {@link ApiKeyEndpointIT}.
 */
@SpringBootTest
class TenantProvisioningIT extends AbstractPostgresIT {

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private TenantRepository tenants;

  @Test
  void createsTheTenantOnFirstCallAndReusesItOnSecond() {
    String subject = "kc-sub-" + UUID.randomUUID();
    assertThat(tenants.findByExternalSubject(subject)).isEmpty();

    TenantEntity first = provisioning.provision(subject, "Test User");
    // Re-read from the database so the timestamp is compared at Postgres precision on both sides (a
    // freshly built entity carries nanosecond Instants that a round-trip truncates to
    // microseconds).
    TenantEntity firstPersisted = tenants.findByExternalSubject(subject).orElseThrow();
    assertThat(firstPersisted.getId()).isEqualTo(first.getId());

    TenantEntity second = provisioning.provision(subject, "A Different Name");
    // Same row: not recreated, and the display name from the first sight is kept (idempotent).
    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(second.getCreatedAt()).isEqualTo(firstPersisted.getCreatedAt());
    assertThat(second.getDisplayName()).isEqualTo("Test User");
    assertThat(countTenantsFor(subject)).isEqualTo(1L);
  }

  @Test
  void concurrentFirstRequestsProvisionExactlyOneTenant() throws Exception {
    String subject = "kc-sub-" + UUID.randomUUID();
    int racers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch startGun = new CountDownLatch(1);
    List<Future<UUID>> results = new ArrayList<>();
    try {
      for (int i = 0; i < racers; i++) {
        results.add(
            pool.submit(
                () -> {
                  startGun.await();
                  return provisioning.provision(subject, "Race").getId();
                }));
      }
      startGun.countDown(); // release all racers at once

      Set<UUID> resolvedIds = new HashSet<>();
      for (Future<UUID> result : results) {
        resolvedIds.add(result.get());
      }
      // Every concurrent first request resolved to the same tenant, and exactly one row exists —
      // the unique(external_subject) constraint let one insert win, the losers adopted it.
      assertThat(resolvedIds).hasSize(1);
      assertThat(countTenantsFor(subject)).isEqualTo(1L);
    } finally {
      pool.shutdownNow();
    }
  }

  private long countTenantsFor(String subject) {
    return tenants.findAll().stream().filter(t -> subject.equals(t.getExternalSubject())).count();
  }
}
