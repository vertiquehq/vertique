// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisTestFixtures.REDIS_CONFIG;
import static dev.vertique.cache.redis.RedisTestFixtures.REGION;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.redis.RedisPrimaryNode;
import dev.vertique.redis.RedisScanPage;
import dev.vertique.redis.RedisTopologyOperations;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Proves the pinned Redis command behavior through the frozen topology seam. */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RedisCleanupIT {
    private static final String REDIS_IMAGE =
            "redis:7.2.4-alpine@sha256:c8bb255c3559b3e458766db810aa7b3c7af1235b204cfdb304e79ff388fe1a5a";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private static RedisAPI commands;
    private static Redis client;

    @BeforeAll
    static void setUp(Vertx vertx) {
        client = Redis.createClient(
                vertx,
                new io.vertx.redis.client.RedisOptions()
                        .setConnectionString("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)));
        commands = RedisAPI.api(client);
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("SCAN returns the physical old-generation key and UNLINK removes it")
    void executesScanAndUnlinkAgainstPinnedRedis() throws Exception {
        String generationKey = RedisCacheKey.generation(REGION, REDIS_CONFIG, cacheConfig());
        String oldKey = "it:v1:cache:v1:profiles:gOLD:NONE:redis-command-proof";
        await(commands.set(List.of(generationKey, "CURRENT")));
        await(commands.set(List.of(oldKey, "value")));

        RedisTopologyOperations topology = new PinnedRedisTopology(commands, new RedisPrimaryNode("standalone"));
        RedisScanPage page = await(topology.scan(new RedisPrimaryNode("standalone"), "0", 10));
        long deleted = await(topology.unlink(new RedisPrimaryNode("standalone"), page.keys()));
        Response remaining = await(commands.get(oldKey));

        assertEquals(
                List.of(oldKey), page.keys().stream().filter(oldKey::equals).toList());
        assertEquals(1L, deleted);
        assertEquals(null, remaining);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private record PinnedRedisTopology(RedisAPI commands, RedisPrimaryNode node) implements RedisTopologyOperations {
        @Override
        public Future<List<RedisPrimaryNode>> primaryNodes() {
            return Future.succeededFuture(List.of(node));
        }

        @Override
        public Future<RedisScanPage> scan(RedisPrimaryNode requested, String cursor, int count) {
            return commands.scan(List.of(cursor, "COUNT", Integer.toString(count)))
                    .map(response -> new RedisScanPage(
                            response.get(0).toString(),
                            response.get(1).stream().map(Response::toString).toList(),
                            "0".equals(response.get(0).toString())));
        }

        @Override
        public Future<Long> unlink(RedisPrimaryNode requested, List<String> keys) {
            return commands.unlink(keys).map(Response::toLong);
        }
    }
}
