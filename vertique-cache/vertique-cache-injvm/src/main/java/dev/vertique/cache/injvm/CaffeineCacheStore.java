// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.injvm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;

/** Bounded local cache store backed by Caffeine. */
@Singleton
public final class CaffeineCacheStore implements CacheStore {
    private final Cache<CacheKey, Object> entries;

    @Inject
    public CaffeineCacheStore(CacheConfig config) {
        this.entries =
                Caffeine.newBuilder().maximumSize(config.maximumEntries()).build();
    }

    @Override
    public Future<Optional<Object>> get(CacheKey key, Type declaredType) {
        return Future.succeededFuture(Optional.ofNullable(entries.getIfPresent(key)));
    }

    @Override
    public Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl) {
        if (!ttl.isNegative() && !ttl.isZero()) {
            entries.put(key, value);
        } else if (ttl.isZero()) {
            entries.put(key, value);
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> evict(CacheKey key) {
        entries.invalidate(key);
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> clear(CacheRegion region) {
        entries.asMap().keySet().removeIf(key -> key.region().equals(region));
        return Future.succeededFuture();
    }
}
