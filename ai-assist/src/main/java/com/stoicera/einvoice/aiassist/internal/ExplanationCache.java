package com.stoicera.einvoice.aiassist.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded, least-recently-used cache of explanations, keyed as SPEC §6 asks: the rule id plus a
 * hash of the exact (already-scrubbed) text the model was asked about.
 *
 * <p>The hit rate is the point. A corpus of rejected invoices produces the same handful of rule
 * violations over and over — {@code AT-B2G-01} and {@code PEPPOL-EN16931-R010} are most of the
 * traffic on a public validator — and the explanation for a given rule and a given quoted value
 * does not change between callers. Every hit is one fewer paid provider call and one less document
 * value leaving the platform, so the cache is a cost control and a privacy control at once.
 *
 * <p><strong>Bounded, and in memory only.</strong> {@link #DEFAULT_CAPACITY} entries evicted
 * least-recently-used first: an unbounded map fed by a public endpoint is a memory-exhaustion
 * vector, which is the same reasoning that bounds {@code RateLimitFilter}'s bucket map. Nothing is
 * persisted — an explanation is derived data, and the public validator's promise is that an upload
 * leaves no trace.
 *
 * <p>Single-instance, like the rate limiter: a horizontally scaled deployment would want a shared
 * cache, and this one would simply have a lower hit rate rather than being wrong. Said out loud
 * rather than implied.
 *
 * <p>Thread-safe by synchronizing every access. {@code LinkedHashMap} in access order is not
 * concurrent, and the critical section is a hash lookup — a lock here is cheaper than the provider
 * call it saves, by four orders of magnitude.
 */
public final class ExplanationCache {

  /**
   * Entries retained. Generous relative to the number of distinct rules the two rule sets contain
   * (low hundreds), so in practice the cache holds every rule seen with every distinct value seen
   * recently, while still being a few hundred kilobytes at worst.
   */
  public static final int DEFAULT_CAPACITY = 500;

  private final int capacity;
  private final Map<String, String> entries;

  public ExplanationCache() {
    this(DEFAULT_CAPACITY);
  }

  public ExplanationCache(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException(
          "Explanation cache capacity must be positive, was " + capacity);
    }
    this.capacity = capacity;
    this.entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > ExplanationCache.this.capacity;
          }
        };
  }

  /** The cached explanation for {@code key}, if one is held. */
  public synchronized Optional<String> get(String key) {
    return Optional.ofNullable(entries.get(key));
  }

  /**
   * Stores {@code explanation} under {@code key}, evicting the least-recently-used entry if full.
   */
  public synchronized void put(String key, String explanation) {
    entries.put(key, explanation);
  }

  /** Entries currently held — for tests and for the eviction assertion. */
  public synchronized int size() {
    return entries.size();
  }
}
