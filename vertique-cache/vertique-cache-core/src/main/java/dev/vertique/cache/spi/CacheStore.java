// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import io.vertx.core.Future;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;

/** Provider-neutral object-facing cache storage extension point. */
public interface CacheStore {
    /** Final provider seam. Implementations should consume only resolved runtime data. */
    default Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value) {
        return get(new CacheKey(key.region(), key.identityComponent(), key.selector()), value.type());
    }

    /** Final provider seam. Implementations should consume only resolved runtime data. */
    default Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor value, Object object, Duration ttl) {
        return put(new CacheKey(key.region(), key.identityComponent(), key.selector()), object, value.type(), ttl);
    }

    /** Final provider seam. Implementations should consume only resolved runtime data. */
    default Future<Void> evict(ResolvedCacheKey key) {
        return evict(new CacheKey(key.region(), key.identityComponent(), key.selector()));
    }

    Future<Void> clear(CacheRegion region);

    /** @deprecated Implement the resolved-key overload instead. */
    @Deprecated
    default Future<Optional<Object>> get(CacheKey key, Type declaredType) {
        return Future.failedFuture(new UnsupportedOperationException("legacy cache store get is not implemented"));
    }

    /** @deprecated Implement the resolved-key overload instead. */
    @Deprecated
    default Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl) {
        return Future.failedFuture(new UnsupportedOperationException("legacy cache store put is not implemented"));
    }

    /** @deprecated Implement the resolved-key overload instead. */
    @Deprecated
    default Future<Void> evict(CacheKey key) {
        return Future.failedFuture(new UnsupportedOperationException("legacy cache store evict is not implemented"));
    }
}
