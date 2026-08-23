// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

/** Optional provider-neutral observation seam for cache operations. */
public interface CacheObserver {
    void onOperation(CacheObservation observation);
}
