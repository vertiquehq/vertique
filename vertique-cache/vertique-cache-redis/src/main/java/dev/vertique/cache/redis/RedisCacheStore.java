// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.Future;
import io.vertx.redis.client.Response;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Redis-backed provider-neutral cache store using the shared lazy client registry. */
@Singleton
public final class RedisCacheStore implements CacheStore {
    private final RedisCommandClient redis;
    private final CacheRedisConfig config;
    private final CacheConfig cacheConfig;
    private final JsonMapperProfileRegistry profiles;
    /** Direct-provider constructor for use without a Dagger graph. */
    public RedisCacheStore(RedisClientRegistry clients, CacheRedisConfig config, CacheConfig cacheConfig) {
        this(
                VertxRedisCommandClient.from(clients, config.connection()),
                config,
                cacheConfig,
                new DefaultJsonMapperProfileRegistry(Set.of()));
    }

    @Inject
    public RedisCacheStore(
            RedisClientRegistry clients,
            CacheRedisConfig config,
            CacheConfig cacheConfig,
            JsonMapperProfileRegistry profiles) {
        this(VertxRedisCommandClient.from(clients, config.connection()), config, cacheConfig, profiles);
    }

    RedisCacheStore(
            RedisCommandClient redis,
            CacheRedisConfig config,
            CacheConfig cacheConfig,
            JsonMapperProfileRegistry profiles) {
        this.redis = redis;
        this.config = config;
        this.cacheConfig = cacheConfig;
        this.profiles = profiles;
    }

    @Override
    public Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value) {
        return getResolved(key, value);
    }

    private Future<Optional<Object>> getResolved(ResolvedCacheKey key, CacheValueDescriptor value) {
        if (!cacheConfig.enabled()) {
            return Future.succeededFuture(Optional.empty());
        }
        try {
            return (generation(key).compose(token -> redis.get(entryKey(key, token))))
                    .compose(response -> decode(response, key.region(), value));
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    @Override
    public Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
        return putResolved(key, descriptor, value, ttl);
    }

    private Future<Void> putResolved(
            ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
        if (!cacheConfig.enabled() || value == null || ttl.isNegative()) {
            return Future.succeededFuture();
        }
        final String json;
        try {
            json = mapper(descriptor.jsonProfile()).writeValueAsString(value);
        } catch (Exception failure) {
            return Future.failedFuture(failure);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > cacheConfig.maxValueBytes()) {
            return Future.failedFuture("Redis cache value exceeds maxValueBytes");
        }
        try {
            return (generation(key).compose(token -> {
                String storageKey = entryKey(key, token);
                List<String> command = ttl.isZero()
                        ? List.of(storageKey, json)
                        : List.of(storageKey, json, "PX", Long.toString(ttlMillis(ttl)));
                return redis.set(command).mapEmpty();
            }));
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    @Override
    public Future<Void> evict(ResolvedCacheKey key) {
        if (!cacheConfig.enabled()) {
            return Future.succeededFuture();
        }
        try {
            return (generation(key)
                    .compose(token -> redis.del(List.of(entryKey(key, token))).mapEmpty()));
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    @Override
    public Future<Void> clear(CacheRegion region) {
        if (!cacheConfig.enabled()) {
            return Future.succeededFuture();
        }
        try {
            String generationKey = RedisCacheKey.generation(region, config, cacheConfig);
            return (redis.set(List.of(generationKey, UUID.randomUUID().toString()))
                    .mapEmpty());
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    private Future<String> generation(ResolvedCacheKey key) {
        String candidate = UUID.randomUUID().toString();
        try {
            entryKey(key, candidate);
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
        return generation(key.region(), candidate);
    }

    private Future<String> generation(CacheRegion region, String candidate) {
        final String generationKey;
        try {
            generationKey = RedisCacheKey.generation(region, config, cacheConfig);
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
        return redis.set(List.of(generationKey, candidate, "NX"))
                .compose(ignored -> redis.get(generationKey))
                .map(response -> {
                    String generation = response == null ? null : response.toString();
                    if (generation == null || generation.isBlank()) {
                        throw new IllegalStateException("Redis cache generation key was not initialized");
                    }
                    return generation;
                });
    }

    private String entryKey(ResolvedCacheKey key, String generation) {
        return RedisCacheKey.entry(key, generation, config, cacheConfig);
    }

    private static long ttlMillis(Duration ttl) {
        try {
            return Math.max(1L, ttl.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private Future<Optional<Object>> decode(Response response, CacheRegion region, CacheValueDescriptor value) {
        String json = response == null ? null : response.toString();
        if (json == null || "null".equalsIgnoreCase(json)) {
            return Future.succeededFuture(Optional.empty());
        }
        try {
            ObjectMapper mapper = mapper(value.jsonProfile());
            Object decoded = mapper.readValue(json, mapper.constructType(value.type()));
            return decoded == null
                    ? Future.succeededFuture(Optional.empty())
                    : Future.succeededFuture(Optional.of(decoded));
        } catch (Exception failure) {
            return Future.failedFuture(failure);
        }
    }

    private ObjectMapper mapper(String profile) {
        return profiles.mapper(JsonProfileId.of(profile));
    }
}
