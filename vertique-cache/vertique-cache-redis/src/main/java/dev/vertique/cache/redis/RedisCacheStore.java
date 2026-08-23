// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.redis.client.RedisAPI;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Redis-backed provider-neutral cache store using the shared lazy client registry. */
@Singleton
public final class RedisCacheStore implements CacheStore {
    private final RedisAPI redis;
    private final CacheRedisConfig config;
    private final CacheConfig cacheConfig;

    @Inject
    public RedisCacheStore(RedisClientRegistry clients, CacheRedisConfig config, CacheConfig cacheConfig) {
        this.redis = RedisAPI.api(clients.client(config.connection()));
        this.config = config;
        this.cacheConfig = cacheConfig;
    }

    @Override
    public Future<Optional<Object>> get(CacheKey key, Type declaredType) {
        return generation(key.region())
                .compose(token -> redis.get(storageKey(key, token)))
                .map(response -> {
                    if (response == null
                            || response.toString() == null
                            || "null".equalsIgnoreCase(response.toString())) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(Json.decodeValue(response.toString(), Object.class));
                });
    }

    @Override
    public Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl) {
        String json = Json.encode(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > cacheConfig.maxValueBytes()) {
            return Future.failedFuture("Redis cache value exceeds maxValueBytes");
        }
        return generation(key.region()).compose(token -> {
            List<String> command = ttl.isZero()
                    ? List.of(storageKey(key, token), json)
                    : List.of(storageKey(key, token), json, "PX", Long.toString(Math.max(1, ttl.toMillis())));
            return redis.set(command).mapEmpty();
        });
    }

    @Override
    public Future<Void> evict(CacheKey key) {
        return generation(key.region())
                .compose(token -> redis.del(List.of(storageKey(key, token))).mapEmpty());
    }

    @Override
    public Future<Void> clear(CacheRegion region) {
        return redis.set(List.of(generationKey(region), UUID.randomUUID().toString()))
                .mapEmpty();
    }

    private Future<String> generation(CacheRegion region) {
        String generationKey = generationKey(region);
        String candidate = UUID.randomUUID().toString();
        return redis.set(List.of(generationKey, candidate, "NX"))
                .compose(ignored -> redis.get(generationKey))
                .map(response -> {
                    if (response == null
                            || response.toString() == null
                            || response.toString().isBlank()) {
                        throw new IllegalStateException("Redis cache generation key was not initialized");
                    }
                    return response.toString();
                });
    }

    private String storageKey(CacheKey key, String generation) {
        return config.namespace() + ":" + key.region().canonicalPrefix() + ":g" + generation + ":"
                + key.identityComponent() + ":" + key.selector();
    }

    private String generationKey(CacheRegion region) {
        return config.namespace() + ":" + region.canonicalPrefix() + ":generation";
    }
}
