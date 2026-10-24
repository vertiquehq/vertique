// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import dev.vertique.cache.spi.CacheObserver;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Factory for {@link ObjectCache} instances. */
public final class ObjectCaches {

    private ObjectCaches() {}

    /**
     * Creates a by-reference object cache bounded to {@code maxEntries}. Misses, loads and size evictions
     * are reported to the observers as cache operation events under provider id {@code caffeine-object}.
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
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cacheFailure, "cacheFailure");
        Objects.requireNonNull(observers, "observers");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        return new CaffeineObjectCache<>(name, maxEntries, cacheFailure, Set.copyOf(observers));
    }
}
