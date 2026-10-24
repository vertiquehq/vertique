// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import io.vertx.core.Future;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * By-reference, size-bounded, single-flight cache of runtime objects. Values are never serialized, entries
 * have no time to live, and the cache is independent of {@code cache.enabled}.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface ObjectCache<K, V> {

    /**
     * Returns the cached value, or runs {@code loader} once per key across concurrent callers. A failed
     * future is not cached unless the cache's failure predicate accepts its error. Each caller gets its own
     * future over the shared in-flight load; it completes on the caller's current Vert.x context, or on the
     * completing thread when there is none.
     *
     * @param key the cache key
     * @param loader produces the value on a miss
     * @return a future of the cached or freshly loaded value
     */
    Future<V> getOrLoad(K key, Supplier<Future<V>> loader);

    /**
     * Returns the successfully loaded value for the key, if resident.
     *
     * @param key the cache key
     * @return the resident value, or empty
     */
    Optional<V> getIfPresent(K key);

    /**
     * Removes the entry for the key. Emits no event.
     *
     * @param key the cache key
     */
    void invalidate(K key);

    /** Returns the number of resident entries, including cached failures. */
    long size();

    /** Returns the cache name used as the event cache name. */
    String name();
}
