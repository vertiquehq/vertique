// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisTestFixtures.KEY;
import static dev.vertique.cache.redis.RedisTestFixtures.await;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static dev.vertique.cache.redis.RedisTestFixtures.descriptor;
import static dev.vertique.cache.redis.RedisTestFixtures.profiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.cache.spi.CacheStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies that a pending Redis operation does not block other event-loop work. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RedisEventLoopIT {

    @Test
    @DisplayName("Redis operations leave the Vert.x event loop responsive")
    void operationsDoNotBlockEventLoop(Vertx vertx) throws Exception {
        RedisTestFixtures.ControllableRedisCommandClient commands =
                new RedisTestFixtures.ControllableRedisCommandClient();
        commands.blockEntryReads();
        CacheStore store = new RedisCacheStore(
                commands,
                RedisTestFixtures.REDIS_CONFIG,
                cacheConfig(),
                profiles("vertx", new ObjectMapper()),
                new VertxRedisDeadline(vertx));

        Future<Optional<Object>> operation = store.get(KEY, descriptor(String.class));
        assertTrue(commands.awaitEntryReadStarted(), "the provider must reach the controllable backend read");

        CountDownLatch eventLoopMarker = new CountDownLatch(1);
        vertx.runOnContext(ignored -> eventLoopMarker.countDown());
        assertTrue(eventLoopMarker.await(2, TimeUnit.SECONDS), "event-loop work must run while Redis is pending");

        commands.releaseEntryRead();
        assertEquals(Optional.empty(), await(operation));
    }
}
