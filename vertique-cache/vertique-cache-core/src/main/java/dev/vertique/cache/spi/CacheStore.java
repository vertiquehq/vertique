// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.Optional;

/** Provider-neutral object-facing cache storage extension point. */
public interface CacheStore {
    /** Final provider seam. Implementations should consume only resolved runtime data. */
    Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value);

    /** Final provider seam. Implementations should consume only resolved runtime data. */
    Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor value, Object object, Duration ttl);

    /** Final provider seam. Implementations should consume only resolved runtime data. */
    Future<Void> evict(ResolvedCacheKey key);

    Future<Void> clear(CacheRegion region);
}
