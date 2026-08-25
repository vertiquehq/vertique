// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies cleanup unregisters before the shared Redis client is closed. */
class RedisCleanupLifecycleTest {

    @Test
    @DisplayName("unregisters cleanup before closing the shared Redis client")
    void unregistersBeforeRedisClientClose() throws Exception {
        List<String> order = new ArrayList<>();
        RedisCleanupJob job = RedisCleanupJobTestSupport.job();
        RedisCleanupLifecycle lifecycle = new RedisCleanupLifecycle(
                job,
                () -> {
                    order.add("cleanup-unregister");
                    return Future.succeededFuture();
                },
                () -> {
                    order.add("redis-client-close");
                    return Future.succeededFuture();
                });

        await(lifecycle.stop());

        assertEquals(List.of("cleanup-unregister", "redis-client-close"), order);
    }

    @Test
    @DisplayName("closes Redis and preserves unregister failure")
    void closesRedisWhenUnregisterFails() {
        List<String> order = new ArrayList<>();
        RuntimeException unregisterFailure = new RuntimeException("cleanup unregister failed");
        RuntimeException closeFailure = new RuntimeException("Redis client close failed");
        RedisCleanupJob job = RedisCleanupJobTestSupport.job();
        RedisCleanupLifecycle lifecycle = new RedisCleanupLifecycle(
                job,
                () -> {
                    order.add("cleanup-unregister");
                    return Future.failedFuture(unregisterFailure);
                },
                () -> {
                    order.add("redis-client-close");
                    return Future.failedFuture(closeFailure);
                });

        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(lifecycle.stop()));

        assertSame(unregisterFailure, failure.getCause());
        assertEquals(List.of("cleanup-unregister", "redis-client-close"), order);
    }

    @Test
    @DisplayName("propagates Redis close failure after successful unregister")
    void propagatesRedisCloseFailure() {
        RuntimeException closeFailure = new RuntimeException("Redis client close failed");
        RedisCleanupJob job = RedisCleanupJobTestSupport.job();
        RedisCleanupLifecycle lifecycle =
                new RedisCleanupLifecycle(job, Future::succeededFuture, () -> Future.failedFuture(closeFailure));

        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(lifecycle.stop()));

        assertSame(closeFailure, failure.getCause());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
