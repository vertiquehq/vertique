// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

/** Optional provider-neutral observation seam for cache operations and cleanup outcomes. */
public interface CacheObserver {
    /**
     * Observes one cache operation.
     *
     * @param observation redacted cache operation data
     */
    void onOperation(CacheObservation observation);

    /**
     * Observes one physical cache cleanup outcome.
     *
     * <p>The default implementation keeps operation-only observers source-compatible.
     *
     * @param observation redacted, bounded cleanup outcome data
     */
    default void onCleanup(CacheCleanupObservation observation) {}
}
