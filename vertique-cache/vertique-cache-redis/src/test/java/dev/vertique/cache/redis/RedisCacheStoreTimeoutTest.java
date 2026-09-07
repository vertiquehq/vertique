// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisTestFixtures.KEY;
import static dev.vertique.cache.redis.RedisTestFixtures.await;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static dev.vertique.cache.redis.RedisTestFixtures.descriptor;
import static dev.vertique.cache.redis.RedisTestFixtures.profiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.aop.Invocation;
import dev.vertique.cache.AnonymousCachePolicy;
import dev.vertique.cache.Cache;
import dev.vertique.cache.CacheAdapterSupport;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.aop.Cacheable;
import dev.vertique.cache.aop.CacheableAspect;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheLateCompletion;
import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the Redis store is unfenced and the core runtime alone owns the
 * caller-visible deadline, including the late-completion telemetry a store-owned
 * settlement fence would suppress.
 */
class RedisCacheStoreTimeoutTest {

    @Test
    @DisplayName("store futures are unfenced: a hung backend keeps the store future pending")
    void storeFuturesAreUnfenced() throws Exception {
        RedisTestFixtures.ControllableRedisCommandClient commands =
                new RedisTestFixtures.ControllableRedisCommandClient();
        commands.blockEntryReads();
        RedisCacheStore store = store(commands);

        Future<Optional<Object>> operation = store.get(KEY, descriptor(String.class));
        assertTrue(commands.awaitEntryReadStarted(), "the provider must reach the controllable backend read");
        Thread.sleep(cacheConfig().backendTimeoutMs() * 2);
        assertFalse(operation.isComplete(), "the store must not own a settlement fence");

        commands.releaseEntryRead();
        assertEquals(Optional.empty(), await(operation));
    }

    @Test
    @DisplayName("a hung get fails open through the core deadline and later reports a late completion")
    void hungGetFailsOpenAndReportsLateCompletion() throws Exception {
        RedisTestFixtures.ControllableRedisCommandClient commands =
                new RedisTestFixtures.ControllableRedisCommandClient();
        commands.blockEntryReads();
        List<CacheEvent> events = new CopyOnWriteArrayList<>();
        CacheObserver observer = events::add;
        Method method = BusinessTarget.class.getDeclaredMethod("load");

        Object result = await(cacheableInvocation(store(commands), observer, method));

        assertEquals("business-result", result, "the core deadline must fail open to the loader");
        assertTrue(
                observed(
                        events,
                        event -> event instanceof CacheOperationCompleted completed
                                && completed.operation() == CacheOperation.GET
                                && completed.outcome() == CacheOutcome.TIMEOUT),
                () -> "expected a GET timeout, saw " + events);

        commands.releaseEntryRead();
        // The fail-open loader already wrote the value back, so the late settlement
        // reports the entry it finally found: the "would have been a hit" case.
        assertTrue(
                awaitObserved(
                        events,
                        event -> event instanceof CacheLateCompletion late
                                && late.operation() == CacheOperation.GET
                                && late.outcome() == CacheOutcome.HIT),
                () -> "expected a late GET settlement, saw " + events);
    }

    @Test
    @DisplayName("a timed-out exact eviction cannot replace the successful business result")
    void evictTimeoutCannotChangeBusinessOutcome() throws Exception {
        RedisTestFixtures.ControllableRedisCommandClient commands =
                new RedisTestFixtures.ControllableRedisCommandClient();
        commands.blockNextDelete();
        List<CacheEvent> events = new CopyOnWriteArrayList<>();
        CacheObserver observer = events::add;

        Future<Object> result = evictionInvocation(store(commands), observer);
        assertEquals("business-result", await(result));
        assertTrue(
                observed(
                        events,
                        event -> event instanceof CacheOperationCompleted completed
                                && completed.operation() == CacheOperation.EVICT
                                && completed.outcome() == CacheOutcome.TIMEOUT),
                () -> "expected an EVICT timeout, saw " + events);

        commands.completeDelete();
        assertTrue(
                awaitObserved(
                        events,
                        event -> event instanceof CacheLateCompletion late
                                && late.operation() == CacheOperation.EVICT
                                && late.outcome() == CacheOutcome.SUCCESS),
                () -> "expected a late EVICT settlement, saw " + events);
        assertEquals("business-result", await(result));
    }

    private static boolean observed(List<CacheEvent> events, Predicate<CacheEvent> match) {
        return events.stream().anyMatch(match);
    }

    private static boolean awaitObserved(List<CacheEvent> events, Predicate<CacheEvent> match)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (observed(events, match)) {
                return true;
            }
            Thread.sleep(10);
        }
        return observed(events, match);
    }

    private static RedisCacheStore store(RedisCommandClient commands) {
        return RedisTestFixtures.store(commands, cacheConfig(), profiles("system", new ObjectMapper()));
    }

    private static Future<Object> cacheableInvocation(RedisCacheStore store, CacheObserver observer, Method method) {
        MethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of());
        Invocation invocation = invocation(metadata);
        return new CacheableAspect(store, cacheConfig(), Set.of(observer))
                .interceptor(metadata, method.getAnnotation(Cacheable.class))
                .intercept(invocation);
    }

    private static Future<Object> evictionInvocation(RedisCacheStore store, CacheObserver observer) {
        CacheAdapterSupport support = CacheAdapterSupport.forStore(store, cacheConfig(), Set.of(observer), Set.of());
        Cache<Object, Object> cache = support.registered(new CacheAdapterSupport.AdapterDefinition(
                "profiles",
                String.class,
                CacheMode.CLUSTERED,
                -1,
                CacheIdentity.NONE,
                AnonymousCachePolicy.BYPASS,
                null,
                false,
                List.of()));
        return Future.succeededFuture("business-result")
                .compose(result -> cache.invalidate(new Object()).map(ignored -> result));
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
        @Cacheable(
                name = "profiles",
                key = {},
                subject = CacheIdentity.NONE)
        Future<String> load() {
            return Future.succeededFuture("unused");
        }
    }
}
