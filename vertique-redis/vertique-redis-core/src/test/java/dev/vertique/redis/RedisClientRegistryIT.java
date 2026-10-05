// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-owned Testcontainers proof for {@link RedisClientRegistry}: real plaintext Redis
 * round-trip through {@code client(name)} and idempotent registry close.
 */
@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RedisClientRegistryIT {

    /** Same pinned image {@code vertique-rate-limit-redis} / cache Redis ITs use. */
    private static final String REDIS_IMAGE =
            "redis:7.2.4-alpine@sha256:c8bb255c3559b3e458766db810aa7b3c7af1235b204cfdb304e79ff388fe1a5a";

    private static final String PROFILE = "primary";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

    private Vertx vertx;
    private RedisClientRegistry registry;

    @AfterEach
    void tearDown() throws Exception {
        if (registry != null) {
            await(registry.close());
            registry = null;
        }
        if (vertx != null) {
            await(vertx.close());
            vertx = null;
        }
    }

    @Test
    @DisplayName("client(name) round-trips SET/GET against a real Redis and close is idempotent")
    void clientRoundTripAndIdempotentClose() throws Exception {
        vertx = Vertx.vertx();
        registry = new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(profile())));

        Redis first = registry.client(PROFILE);
        Redis second = registry.client(PROFILE);
        assertSame(first, second, "one client must be reused for a profile");

        String key = "vertique:redis-core:it:" + UUID.randomUUID();
        String value = "ok";
        await(first.send(Request.cmd(Command.SET).arg(key).arg(value)));
        Response got = await(first.send(Request.cmd(Command.GET).arg(key)));
        assertEquals(value, got.toString());

        Future<Void> firstClose = registry.close();
        Future<Void> secondClose = registry.close();
        await(firstClose);
        await(secondClose);
        assertSame(firstClose, secondClose, "repeated close must share one future");

        assertThrows(IllegalStateException.class, () -> registry.client(PROFILE));
    }

    private static RedisConnectionConfig profile() {
        return new RedisConnectionConfig(
                PROFILE,
                List.of("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)),
                null,
                null,
                false,
                2_000,
                8,
                100);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
