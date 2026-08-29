// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.T011CacheCompositionFixtures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.cache.CacheMode;
import dev.vertique.cache.Cacheable;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.redis.RedisClientRegistry;
import dev.vertique.redis.RedisClientShutdownStep;
import dev.vertique.redis.RedisConnectionConfig;
import dev.vertique.redis.RedisConnectionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Clustered cache lifecycle and disabled/readiness composition proof. */
class CacheLifecycleIT {

    @Test
    @DisplayName("disabled cache bypasses every backend operation")
    void disabledCacheClosesWithoutBackendAccess() throws Exception {
        ThrowingStore backend = new ThrowingStore();
        dev.vertique.cache.CacheableAspect aspect =
                new dev.vertique.cache.CacheableAspect(backend, disabledConfig(), Set.of());
        var valueMethod = LifecycleTarget.class.getDeclaredMethod("value");
        Cacheable annotation = valueMethod.getAnnotation(Cacheable.class);
        var metadata = new dev.vertique.core.codegen.ReflectiveMethodMetadata(valueMethod, List.of());
        var interceptor = aspect.interceptor(metadata, annotation);

        Object result = await(interceptor.intercept(new dev.vertique.aop.Invocation() {
            @Override
            public dev.vertique.core.codegen.MethodMetadata target() {
                return metadata;
            }

            @Override
            public Object[] arguments() {
                return new Object[0];
            }

            @Override
            public Object instance() {
                return new LifecycleTarget();
            }

            @Override
            public Future<Object> proceed() {
                return Future.succeededFuture("business-result");
            }
        }));

        assertEquals("business-result", result);
        assertEquals(0, backend.calls.get(), "disabled cache must not touch the backend");
    }

    @Test
    @DisplayName("cleanup unregisters before the shared Redis client closes")
    void redisCleanupUnregistersBeforeClientClose() throws Exception {
        List<String> order = new ArrayList<>();
        RedisCleanupLifecycle lifecycle = new RedisCleanupLifecycle(
                RedisCleanupJobTestSupport.job(),
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
        assertEquals(LifecyclePhase.INFRA, lifecycle.phase());
    }

    @Test
    @DisplayName("feature cleanup sorts ahead of shared Redis client shutdown")
    void redisClientClosesAfterFeatureConsumers() throws Exception {
        Vertx vertx = Vertx.vertx();
        RedisClientRegistry registry = new RedisClientRegistry(
                vertx,
                new RedisConnectionsConfig(List.of(new RedisConnectionConfig(
                        "primary", List.of("redis://127.0.0.1:6379"), null, null, false, 100, 1, 1))));
        try {
            RedisCleanupLifecycle cleanup = new RedisCleanupLifecycle(
                    RedisCleanupJobTestSupport.job(), Future::succeededFuture, registry::close);
            RedisClientShutdownStep redis = new RedisClientShutdownStep(registry);

            assertTrue(cleanup.priority() > redis.priority());
            assertEquals(LifecyclePhase.INFRA, redis.phase());
        } finally {
            await(registry.close());
            await(vertx.close());
        }
    }

    @Test
    @DisplayName("lazy Redis failure does not block an independent readiness result")
    void redisOutageDoesNotBlockReadiness() throws Exception {
        Vertx vertx = Vertx.vertx();
        RedisClientRegistry registry = new RedisClientRegistry(
                vertx,
                new RedisConnectionsConfig(List.of(new RedisConnectionConfig(
                        "primary", List.of("redis://127.0.0.1:1"), null, null, false, 100, 1, 1))));
        try {
            Future<String> readiness = Future.succeededFuture("ready");

            assertEquals("ready", await(readiness));
            assertFalse(readiness.failed());
        } finally {
            await(registry.close());
            await(vertx.close());
        }
    }

    @Test
    @DisplayName("shutdown is best effort and idempotent")
    void shutdownIsBestEffortAndIdempotent() throws Exception {
        AtomicInteger unregisterCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        RedisCleanupLifecycle lifecycle = new RedisCleanupLifecycle(
                RedisCleanupJobTestSupport.job(),
                () -> {
                    unregisterCalls.incrementAndGet();
                    return Future.succeededFuture();
                },
                () -> {
                    closeCalls.incrementAndGet();
                    return Future.succeededFuture();
                });

        Future<Void> first = lifecycle.stop();
        Future<Void> second = lifecycle.stop();
        await(first);
        await(second);

        assertEquals(1, unregisterCalls.get());
        assertEquals(1, closeCalls.get());
    }

    private static CacheConfig disabledConfig() {
        return new CacheConfig(false, CacheMode.LOCAL, 60, 86_400, "vertx", 1_024, 1_048_576, 100, 100, Map.of());
    }

    static final class LifecycleTarget {
        @Cacheable(
                name = "profiles",
                key = {})
        String value() {
            return "business-result";
        }
    }

    static final class ThrowingStore implements CacheStore {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor descriptor) {
            calls.incrementAndGet();
            return Future.failedFuture("backend accessed");
        }

        @Override
        public Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
            calls.incrementAndGet();
            return Future.failedFuture("backend accessed");
        }

        @Override
        public Future<Void> evict(ResolvedCacheKey key) {
            calls.incrementAndGet();
            return Future.failedFuture("backend accessed");
        }

        @Override
        public Future<Void> clear(CacheRegion region) {
            calls.incrementAndGet();
            return Future.failedFuture("backend accessed");
        }
    }
}
