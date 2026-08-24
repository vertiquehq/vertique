// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import java.nio.charset.StandardCharsets;

/** Package-private canonical Redis key renderer and UTF-8 byte-boundary guard. */
final class RedisCacheKey {
    private RedisCacheKey() {}

    static String generation(CacheRegion region, CacheRedisConfig redisConfig, CacheConfig cacheConfig) {
        return bounded(
                redisConfig.namespace() + ":v" + redisConfig.formatVersion() + ":" + region.canonicalPrefix()
                        + ":generation",
                cacheConfig.maxKeyBytes());
    }

    static String entry(CacheKey key, String generation, CacheRedisConfig redisConfig, CacheConfig cacheConfig) {
        return bounded(
                redisConfig.namespace() + ":v" + redisConfig.formatVersion() + ":"
                        + key.region().canonicalPrefix() + ":g" + generation + ":" + key.identityComponent() + ":"
                        + key.selector(),
                cacheConfig.maxKeyBytes());
    }

    private static String bounded(String key, int maxKeyBytes) {
        int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes > maxKeyBytes) {
            throw new IllegalArgumentException("Redis cache key exceeds maxKeyBytes: " + keyBytes);
        }
        return key;
    }
}
