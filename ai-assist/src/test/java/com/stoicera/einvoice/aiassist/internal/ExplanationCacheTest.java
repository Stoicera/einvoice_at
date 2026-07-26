package com.stoicera.einvoice.aiassist.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExplanationCacheTest {

  @Test
  void returnsWhatWasStored() {
    ExplanationCache cache = new ExplanationCache(4);
    cache.put("AT-B2G-01|abc", "Die Auftragsreferenz fehlt.");

    assertThat(cache.get("AT-B2G-01|abc")).contains("Die Auftragsreferenz fehlt.");
  }

  @Test
  void missesOnAnUnknownKey() {
    assertThat(new ExplanationCache(4).get("nope")).isEmpty();
  }

  @Test
  void evictsTheLeastRecentlyUsedEntryWhenFull() {
    ExplanationCache cache = new ExplanationCache(2);
    cache.put("a", "1");
    cache.put("b", "2");
    cache.get("a"); // "a" is now the most recently used, so "b" is the eviction candidate
    cache.put("c", "3");

    assertThat(cache.size()).isEqualTo(2);
    assertThat(cache.get("a")).contains("1");
    assertThat(cache.get("b")).isEmpty();
    assertThat(cache.get("c")).contains("3");
  }

  @Test
  void staysWithinCapacityUnderManyDistinctKeys() {
    // The memory-exhaustion guard: a public endpoint feeding distinct values must not grow this
    // map.
    ExplanationCache cache = new ExplanationCache(10);
    for (int i = 0; i < 1_000; i++) {
      cache.put("key-" + i, "explanation " + i);
    }

    assertThat(cache.size()).isEqualTo(10);
  }

  @Test
  void rejectsANonPositiveCapacity() {
    assertThatThrownBy(() -> new ExplanationCache(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExplanationCache(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultCapacityIsUsableWithoutArguments() {
    ExplanationCache cache = new ExplanationCache();
    cache.put("a", "1");

    assertThat(cache.get("a")).contains("1");
    assertThat(ExplanationCache.DEFAULT_CAPACITY).isPositive();
  }

  @Test
  void survivesConcurrentWritersWithoutCorruptingItsBound() throws Exception {
    // LinkedHashMap in access order is not concurrent — unsynchronized, this test is how you find
    // out
    // (a corrupted map, a lost eviction, or an infinite loop on resize). Permanent regression guard
    // for the synchronization, in the same spirit as validation's SchematronStage concurrency test.
    ExplanationCache cache = new ExplanationCache(50);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < threads; t++) {
        int id = t;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < 500; i++) {
                    cache.put("t" + id + "-" + i, "e");
                    cache.get("t" + id + "-" + i);
                  }
                  return null;
                }));
      }
      start.countDown();
      // Every future is joined: a task that dies inside the executor would otherwise be swallowed
      // and
      // this test would pass without ever exercising the contended path.
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(cache.size()).isEqualTo(50);
  }
}
