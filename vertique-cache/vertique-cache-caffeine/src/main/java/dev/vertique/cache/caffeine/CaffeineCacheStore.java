// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.config.CacheEntryConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
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
    private final Map<CacheRegion, Cache<CacheKey, Entry>> caches = new ConcurrentHashMap<>();

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
    public Future<Optional<Object>> get(CacheKey key, Type declaredType) {
        if (!config.enabled()) {
            return Future.succeededFuture(Optional.empty());
        }
        Cache<CacheKey, Entry> cache = caches.get(key.region());
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
            ObjectMapper mapper = mapper(key.region());
            Object value = mapper.readValue(entry.bytes(), mapper.constructType(declaredType));
            return value == null
                    ? Future.succeededFuture(Optional.empty())
                    : Future.succeededFuture(Optional.of(value));
        } catch (Exception failure) {
            cache.invalidate(key);
            return Future.failedFuture(failure);
        }
    }

    @Override
    public Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl) {
        if (!config.enabled() || value == null || ttl.isNegative()) {
            return Future.succeededFuture();
        }
        try {
            ObjectMapper mapper = mapper(key.region());
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
    public Future<Void> evict(CacheKey key) {
        if (config.enabled()) {
            Cache<CacheKey, Entry> cache = caches.get(key.region());
            if (cache != null) {
                cache.invalidate(key);
            }
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> clear(CacheRegion region) {
        if (config.enabled()) {
            Cache<CacheKey, Entry> cache = caches.get(region);
            if (cache != null) {
                cache.invalidateAll();
            }
        }
        return Future.succeededFuture();
    }

    private Cache<CacheKey, Entry> newCache(CacheRegion ignored) {
        return Caffeine.newBuilder().maximumSize(config.maximumEntries()).build();
    }

    private ObjectMapper mapper(CacheRegion region) {
        CacheEntryConfig entry = config.caches().get(region.name());
        String profile = entry != null && entry.jsonProfile() != null ? entry.jsonProfile() : config.jsonProfile();
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
