// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisTestFixtures.KEY;
import static dev.vertique.cache.redis.RedisTestFixtures.REDIS_CONFIG;
import static dev.vertique.cache.redis.RedisTestFixtures.await;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static dev.vertique.cache.redis.RedisTestFixtures.get;
import static dev.vertique.cache.redis.RedisTestFixtures.profiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheValueDescriptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies selected JSON profile use, declared-type decoding, and codec fail-open behavior. */
class RedisCacheSerializationTest {

    @Test
    @DisplayName("uses the selected JSON profile and declared value type")
    void usesSelectedJsonProfileAndDeclaredType() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CacheConfig config = cacheConfig("snake");
        RedisTestFixtures.InMemoryRedisCommandClient commands = new RedisTestFixtures.InMemoryRedisCommandClient();
        RedisCacheStore store = RedisTestFixtures.store(
                commands, config, profiles("snake", mapper), new RedisTestFixtures.ImmediateDeadline());
        Profile value = new Profile("Alice", 3);

        await(store.put(KEY, new CacheValueDescriptor(Profile.class, "snake"), value, java.time.Duration.ZERO));
        List<String> valueCommand = commands.setCommands.get(commands.setCommands.size() - 1);

        assertTrue(valueCommand.get(1).contains("\"display_name\""));
        assertFalse(valueCommand.get(1).contains("\"displayName\""));
        assertEquals(Optional.of(value), await(store.get(KEY, new CacheValueDescriptor(Profile.class, "snake"))));
    }

    @Test
    @DisplayName("reports a JSON codec failure to the shared fail-open runtime")
    void codecFailureIsReported() {
        RedisTestFixtures.InMemoryRedisCommandClient commands = new RedisTestFixtures.InMemoryRedisCommandClient();
        String generationKey = RedisCacheKey.generation(KEY.region(), REDIS_CONFIG, cacheConfig());
        String entryKey = RedisCacheKey.entry(KEY, "generation-1", REDIS_CONFIG, cacheConfig());
        commands.values.put(generationKey, "generation-1");
        commands.values.put(entryKey, "not-json");
        RedisCacheStore store = RedisTestFixtures.store(
                commands,
                cacheConfig(),
                profiles("vertx", new ObjectMapper()),
                new RedisTestFixtures.ImmediateDeadline());

        assertThrows(Exception.class, () -> await(get(store, KEY, Profile.class)));
    }

    record Profile(String displayName, int score) {}
}
