// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisTestFixtures.KEY;
import static dev.vertique.cache.redis.RedisTestFixtures.await;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static dev.vertique.cache.redis.RedisTestFixtures.descriptor;
import static dev.vertique.cache.redis.RedisTestFixtures.profiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.aop.Invocation;
import dev.vertique.cache.CacheEvict;
import dev.vertique.cache.CacheEvictAspect;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.Cacheable;
import dev.vertique.cache.CacheableAspect;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies deadline fencing and preservation of the authoritative business result. */
class RedisCacheStoreTimeoutTest {
    private static final Duration BACKEND_DEADLINE = Duration.ofMillis(100);

    @Test
    @DisplayName("includes pool wait in the get deadline and fails open to a miss")
    void getTimeoutIncludesPoolWait() throws Exception {
        RedisTestFixtures.TimeoutDeadline deadline = new RedisTestFixtures.TimeoutDeadline(BACKEND_DEADLINE);
        RedisCacheStore store = timeoutStore(new RedisTestFixtures.InMemoryRedisCommandClient(), deadline);

        Future<Optional<Object>> operation = store.get(KEY, descriptor(String.class));
        assertTrue(operation.failed());
        assertInstanceOf(TimeoutException.class, operation.cause());
        assertEquals(Optional.empty(), await(operation.recover(ignored -> Future.succeededFuture(Optional.empty()))));
        assertEquals(1, deadline.backends().size());
    }

    @Test
    @DisplayName("a timed-out put cannot replace the successful business result")
    void putTimeoutCannotChangeBusinessOutcome() throws Exception {
        RedisTestFixtures.TimeoutDeadline deadline = new RedisTestFixtures.TimeoutDeadline(BACKEND_DEADLINE);
        RedisCacheStore store = timeoutStore(new RedisTestFixtures.InMemoryRedisCommandClient(), deadline);
        Method method = BusinessTarget.class.getDeclaredMethod("load");
        Future<Object> result = cacheableInvocation(store, method);

        assertEquals("business-result", await(result));
    }

    @Test
    @DisplayName("a timed-out exact eviction cannot replace the successful business result")
    void evictTimeoutCannotChangeBusinessOutcome() throws Exception {
        RedisTestFixtures.TimeoutDeadline deadline = new RedisTestFixtures.TimeoutDeadline(BACKEND_DEADLINE);
        RedisCacheStore store = timeoutStore(new RedisTestFixtures.InMemoryRedisCommandClient(), deadline);
        Method method = BusinessTarget.class.getDeclaredMethod("evict");

        assertEquals("business-result", await(evictionInvocation(store, method)));
    }

    @Test
    @DisplayName("a timed-out whole-cache clear cannot replace the successful business result")
    void clearTimeoutCannotChangeBusinessOutcome() throws Exception {
        RedisTestFixtures.TimeoutDeadline deadline = new RedisTestFixtures.TimeoutDeadline(BACKEND_DEADLINE);
        RedisCacheStore store = timeoutStore(new RedisTestFixtures.InMemoryRedisCommandClient(), deadline);
        Method method = BusinessTarget.class.getDeclaredMethod("clear");

        assertEquals("business-result", await(evictionInvocation(store, method)));
    }

    @Test
    @DisplayName("a late backend completion cannot change the already settled outcome")
    void lateCompletionCannotChangeOutcome() throws Exception {
        RedisTestFixtures.ControllableRedisCommandClient commands =
                new RedisTestFixtures.ControllableRedisCommandClient();
        commands.blockNextDelete();
        RedisTestFixtures.TimeoutDeadline deadline = new RedisTestFixtures.TimeoutDeadline(BACKEND_DEADLINE);
        RedisCacheStore store = timeoutStore(commands, deadline);
        Method method = BusinessTarget.class.getDeclaredMethod("evict");

        Future<Object> result = evictionInvocation(store, method);
        assertEquals("business-result", await(result));
        commands.completeDelete();

        assertEquals("business-result", await(result));
    }

    private static RedisCacheStore timeoutStore(
            RedisCommandClient commands, RedisTestFixtures.TimeoutDeadline deadline) {
        return RedisTestFixtures.store(commands, cacheConfig(), profiles("vertx", new ObjectMapper()), deadline);
    }

    private static Future<Object> cacheableInvocation(RedisCacheStore store, Method method) {
        MethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of());
        Invocation invocation = invocation(metadata);
        return new CacheableAspect(store, cacheConfig(), java.util.Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class))
                .intercept(invocation);
    }

    private static Future<Object> evictionInvocation(RedisCacheStore store, Method method) {
        MethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of());
        Invocation invocation = invocation(metadata);
        return new CacheEvictAspect(store, cacheConfig(), java.util.Set.of())
                .interceptor(metadata, method.getAnnotation(CacheEvict.class))
                .intercept(invocation);
    }

    private static Invocation invocation(MethodMetadata metadata) {
        return new Invocation() {
            @Override
            public MethodMetadata target() {
                return metadata;
            }

            @Override
            public Object[] arguments() {
                return new Object[0];
            }

            @Override
            public Object instance() {
                return new BusinessTarget();
            }

            @Override
            public Future<Object> proceed() {
                return Future.succeededFuture("business-result");
            }
        };
    }

    static final class BusinessTarget {
        @Cacheable(name = "profiles", key = "constant", identity = CacheIdentity.NONE)
        Future<String> load() {
            return Future.succeededFuture("unused");
        }

        @CacheEvict(name = "profiles", key = "constant")
        Future<String> evict() {
            return Future.succeededFuture("unused");
        }

        @CacheEvict(name = "profiles", clear = true)
        Future<String> clear() {
            return Future.succeededFuture("unused");
        }
    }
}
