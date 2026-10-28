// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.vertique.cache.spi.CacheObserver;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Factory for {@link ObjectCache} instances. */
public final class ObjectCaches {

    private ObjectCaches() {}

    /**
     * Creates a by-reference object cache bounded to {@code maxEntries} whose entries never expire by time.
     * Misses, loads and size evictions are reported to the observers as cache operation events under provider id
     * {@code caffeine-object}.
     *
     * @param name the cache name; not blank
     * @param maxEntries the maximum number of entries; at least 1
     * @param cacheFailure whether a failed load with the given error is cached as a failed future
     * @param observers the observers that receive cache events
     * @param <K> the key type
     * @param <V> the value type
     * @return the new cache
     * @throws IllegalArgumentException if the name is blank or {@code maxEntries} is below 1
     * @throws NullPointerException if an argument is null
     */
    public static <K, V> ObjectCache<K, V> create(
            String name, long maxEntries, Predicate<Throwable> cacheFailure, Set<CacheObserver> observers) {
        return create(name, maxEntries, Duration.ZERO, cacheFailure, observers);
    }

    /**
     * Creates a by-reference object cache bounded to {@code maxEntries} whose entries expire a fixed time after
     * they were created, like a {@code CacheStore} entry with a finite {@code ttlSeconds}. A zero
     * {@code expireAfterWrite} disables time expiration. Misses, loads and size evictions are reported to the
     * observers as cache operation events under provider id {@code caffeine-object}; an expired entry is removed
     * silently and is not reported as a size eviction. An entry expires from its creation, so a load that outlasts
     * the expiry leaves no entry for later callers to share.
     *
     * @param name the cache name; not blank
     * @param maxEntries the maximum number of entries; at least 1
     * @param expireAfterWrite how long an entry lives; zero for no time expiration
     * @param cacheFailure whether a failed load with the given error is cached as a failed future
     * @param observers the observers that receive cache events
     * @param <K> the key type
     * @param <V> the value type
     * @return the new cache
     * @throws IllegalArgumentException if the name is blank, {@code maxEntries} is below 1 or
     *     {@code expireAfterWrite} is negative
     * @throws NullPointerException if an argument is null
     */
    public static <K, V> ObjectCache<K, V> create(
            String name,
            long maxEntries,
            Duration expireAfterWrite,
            Predicate<Throwable> cacheFailure,
            Set<CacheObserver> observers) {
        return create(name, maxEntries, expireAfterWrite, cacheFailure, observers, Ticker.systemTicker());
    }

    static <K, V> ObjectCache<K, V> create(
            String name,
            long maxEntries,
            Duration expireAfterWrite,
            Predicate<Throwable> cacheFailure,
            Set<CacheObserver> observers,
            Ticker ticker) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expireAfterWrite, "expireAfterWrite");
        Objects.requireNonNull(cacheFailure, "cacheFailure");
        Objects.requireNonNull(observers, "observers");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        if (expireAfterWrite.isNegative()) {
            throw new IllegalArgumentException("expireAfterWrite must not be negative");
        }
        return new CaffeineObjectCache<>(
                name, maxEntries, expireAfterWrite, cacheFailure, Set.copyOf(observers), ticker);
    }
}
