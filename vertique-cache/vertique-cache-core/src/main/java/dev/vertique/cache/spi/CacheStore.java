// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import io.vertx.core.Future;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;

/** Provider-neutral object-facing cache storage extension point. */
public interface CacheStore {
    Future<Optional<Object>> get(CacheKey key, Type declaredType);

    Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl);

    Future<Void> evict(CacheKey key);

    Future<Void> clear(CacheRegion region);
}
