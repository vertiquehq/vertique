// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

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
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Redis-backed provider-neutral cache store using the shared lazy client registry. */
@Singleton
public final class RedisCacheStore implements CacheStore {
    private static final int SCAN_COUNT = 256;

    private final RedisAPI redis;
    private final CacheRedisConfig config;

    @Inject
    public RedisCacheStore(RedisClientRegistry clients, CacheRedisConfig config) {
        this.redis = RedisAPI.api(clients.client(config.connection()));
        this.config = config;
    }

    @Override
    public Future<Optional<Object>> get(CacheKey key, Type declaredType) {
        return redis.get(storageKey(key)).map(response -> {
            if (response == null || response.toString() == null || "null".equalsIgnoreCase(response.toString())) {
                return Optional.empty();
            }
            return Optional.ofNullable(Json.decodeValue(response.toString(), Object.class));
        });
    }

    @Override
    public Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl) {
        String json = Json.encode(value);
        List<String> command = ttl.isZero()
                ? List.of(storageKey(key), json)
                : List.of(storageKey(key), json, "PX", Long.toString(Math.max(1, ttl.toMillis())));
        return redis.set(command).mapEmpty();
    }

    @Override
    public Future<Void> evict(CacheKey key) {
        return redis.del(List.of(storageKey(key))).mapEmpty();
    }

    @Override
    public Future<Void> clear(CacheRegion region) {
        return clearScan("0", region);
    }

    private Future<Void> clearScan(String cursor, CacheRegion region) {
        return redis.scan(List.of(cursor, "MATCH", pattern(region), "COUNT", Integer.toString(SCAN_COUNT)))
                .compose(response -> {
                    List<String> keys =
                            response.get(1).stream().map(Object::toString).toList();
                    Future<Void> deleted = keys.isEmpty()
                            ? Future.succeededFuture()
                            : redis.del(keys).mapEmpty();
                    String nextCursor = response.get(0).toString();
                    return deleted.compose(ignored ->
                            "0".equals(nextCursor) ? Future.succeededFuture() : clearScan(nextCursor, region));
                });
    }

    private String storageKey(CacheKey key) {
        return config.namespace() + ":" + key.canonical();
    }

    private String pattern(CacheRegion region) {
        return config.namespace() + ":" + region.canonicalPrefix() + ":*";
    }
}
