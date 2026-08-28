// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Bounded local cache store backed by per-region Caffeine caches. */
@Singleton
public final class CaffeineCacheStore implements CacheStore {
    private final CacheConfig config;
    private final JsonMapperProfileRegistry profiles;
    private final LongSupplier clock;
    private final Map<CacheRegion, Cache<ResolvedCacheKey, Entry>> caches = new ConcurrentHashMap<>();

    /** Direct-provider constructor for use without a Dagger graph. */
    public CaffeineCacheStore(CacheConfig config) {
        this(config, new DefaultJsonMapperProfileRegistry(Set.of()));
    }

    @Inject
    public CaffeineCacheStore(CacheConfig config, JsonMapperProfileRegistry profiles) {
        this(config, profiles, System::nanoTime);
    }

    CaffeineCacheStore(CacheConfig config, JsonMapperProfileRegistry profiles, LongSupplier clock) {
        this.config = config;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Override
    public Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value) {
        return getResolved(key, value);
    }

    private Future<Optional<Object>> getResolved(ResolvedCacheKey key, CacheValueDescriptor value) {
        if (!config.enabled()) {
            return Future.succeededFuture(Optional.empty());
        }
        Cache<ResolvedCacheKey, Entry> cache = caches.get(key.region());
        if (cache == null) {
            return Future.succeededFuture(Optional.empty());
        }
        cache.cleanUp();
        Entry entry = cache.getIfPresent(key);
        if (entry == null || entry.expired(clock.getAsLong())) {
            if (entry != null) {
                cache.invalidate(key);
            }
            return Future.succeededFuture(Optional.empty());
        }
        try {
            ObjectMapper mapper = mapper(value.jsonProfile());
            Object decoded = mapper.readValue(entry.bytes(), mapper.constructType(value.type()));
            return decoded == null
                    ? Future.succeededFuture(Optional.empty())
                    : Future.succeededFuture(Optional.of(decoded));
        } catch (Exception failure) {
            cache.invalidate(key);
            return Future.failedFuture(failure);
        }
    }

    @Override
    public Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
        return putResolved(key, descriptor, value, ttl);
    }

    private Future<Void> putResolved(
            ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
        if (!config.enabled() || value == null || ttl.isNegative()) {
            return Future.succeededFuture();
        }
        try {
            ObjectMapper mapper = mapper(descriptor.jsonProfile());
            byte[] bytes = mapper.writeValueAsBytes(value);
            if (bytes.length > config.maxValueBytes()) {
                return Future.failedFuture(
                        new IllegalArgumentException("serialized cache value exceeds maxValueBytes: " + bytes.length));
            }
            long expiresAt = ttl.isZero() ? 0 : deadline(ttl);
            caches.computeIfAbsent(key.region(), this::newCache).put(key, new Entry(bytes, expiresAt));
            return Future.succeededFuture();
        } catch (Exception failure) {
            return Future.failedFuture(failure);
        }
    }

    @Override
    public Future<Void> evict(ResolvedCacheKey key) {
        if (config.enabled()) {
            Cache<ResolvedCacheKey, Entry> cache = caches.get(key.region());
            if (cache != null) {
                cache.invalidate(key);
            }
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> clear(CacheRegion region) {
        if (config.enabled()) {
            Cache<ResolvedCacheKey, Entry> cache = caches.get(region);
            if (cache != null) {
                cache.invalidateAll();
            }
        }
        return Future.succeededFuture();
    }

    private Cache<ResolvedCacheKey, Entry> newCache(CacheRegion ignored) {
        return Caffeine.newBuilder().maximumSize(config.maximumEntries()).build();
    }

    private ObjectMapper mapper(String profile) {
        return profiles.mapper(JsonProfileId.of(profile));
    }

    private long deadline(Duration ttl) {
        long now = clock.getAsLong();
        long nanos;
        try {
            nanos = ttl.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        if (nanos > 0 && now > Long.MAX_VALUE - nanos) {
            return Long.MAX_VALUE;
        }
        return now + nanos;
    }

    private record Entry(byte[] bytes, long expiresAtNanos) {
        private boolean expired(long now) {
            return expiresAtNanos != 0 && now >= expiresAtNanos;
        }
    }
}
