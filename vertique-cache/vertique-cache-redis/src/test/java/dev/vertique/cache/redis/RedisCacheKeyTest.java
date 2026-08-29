// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisTestFixtures.KEY;
import static dev.vertique.cache.redis.RedisTestFixtures.REDIS_CONFIG;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.ResolvedCacheKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies canonical Redis key composition and the UTF-8 rendered-key bound. */
class RedisCacheKeyTest {

    @Test
    @DisplayName("includes namespace, format version, and opaque generation in physical keys")
    void includesFormatNamespaceAndGeneration() {
        CacheRegion region = new CacheRegion("cache", "profiles", 7);
        ResolvedCacheKey key = new ResolvedCacheKey(region, "ACTOR:alice", "profile");

        assertEquals(
                "shared:v1:cache:v7:profiles:generation",
                RedisCacheKey.generation(region, new CacheRedisConfig("primary", "shared", 1), cacheConfig()));
        assertEquals(
                "shared:v1:cache:v7:profiles:gopaque-token:ACTOR:alice:profile",
                RedisCacheKey.entry(key, "opaque-token", new CacheRedisConfig("primary", "shared", 1), cacheConfig()));
    }

    @Test
    @DisplayName("uses distinct physical keyspaces for Redis format versions")
    void redisFormatVersionsProduceDistinctPhysicalKeys() {
        CacheRegion region = new CacheRegion("cache", "profiles", 7);
        ResolvedCacheKey key = new ResolvedCacheKey(region, "ACTOR:alice", "profile");
        CacheRedisConfig versionOne = new CacheRedisConfig("primary", "shared", 1);
        CacheRedisConfig versionTwo = new CacheRedisConfig("primary", "shared", 2);

        String generationOne = RedisCacheKey.generation(region, versionOne, cacheConfig());
        String generationTwo = RedisCacheKey.generation(region, versionTwo, cacheConfig());
        String entryOne = RedisCacheKey.entry(key, "opaque-token", versionOne, cacheConfig());
        String entryTwo = RedisCacheKey.entry(key, "opaque-token", versionTwo, cacheConfig());

        assertNotEquals(generationOne, generationTwo);
        assertNotEquals(entryOne, entryTwo);
        assertEquals("shared:v1:cache:v7:profiles:generation", generationOne);
        assertEquals("shared:v2:cache:v7:profiles:generation", generationTwo);
        assertEquals("shared:v1:cache:v7:profiles:gopaque-token:ACTOR:alice:profile", entryOne);
        assertEquals("shared:v2:cache:v7:profiles:gopaque-token:ACTOR:alice:profile", entryTwo);
    }

    @Test
    @DisplayName("rejects an oversized rendered key before backend access")
    void canonicalKeyIsBoundedBeforeHashing() {
        ResolvedCacheKey oversized = new ResolvedCacheKey(KEY.region(), KEY.identityComponent(), "x".repeat(2_000));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> RedisCacheKey.entry(oversized, "generation", REDIS_CONFIG, cacheConfig()));

        assertEquals("Redis cache key exceeds maxKeyBytes: 2041", failure.getMessage());
    }
}
