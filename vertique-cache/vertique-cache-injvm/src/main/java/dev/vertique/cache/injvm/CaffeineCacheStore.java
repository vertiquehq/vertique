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
    private final Cache<CacheKey, Entry> entries;

    @Inject
    public CaffeineCacheStore(CacheConfig config) {
        this.entries =
                Caffeine.newBuilder().maximumSize(config.maximumEntries()).build();
    }

    @Override
    public Future<Optional<Object>> get(CacheKey key, Type declaredType) {
        Entry entry = entries.getIfPresent(key);
        if (entry == null || entry.expired()) {
            if (entry != null) {
                entries.invalidate(key);
            }
            return Future.succeededFuture(Optional.empty());
        }
        return Future.succeededFuture(Optional.of(entry.value()));
    }

    @Override
    public Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl) {
        if (!ttl.isNegative()) {
            entries.put(key, new Entry(value, ttl.isZero() ? 0 : deadline(ttl)));
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

    private static long deadline(Duration ttl) {
        long nanos = ttl.toNanos();
        long now = System.nanoTime();
        long deadline = now + nanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private record Entry(Object value, long expiresAtNanos) {
        private boolean expired() {
            return expiresAtNanos != 0 && System.nanoTime() - expiresAtNanos >= 0;
        }
    }
}
