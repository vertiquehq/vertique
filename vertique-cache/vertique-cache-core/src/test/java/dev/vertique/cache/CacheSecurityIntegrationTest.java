// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.aop.Aspect;
import dev.vertique.aop.Invocations;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves the cache boundary remains inside an already-authorized invocation composition. */
class CacheSecurityIntegrationTest {

    @Test
    @DisplayName("authorization precedes cache lookup on hits and misses")
    void authorizationRunsOnHitAndMiss() throws Exception {
        List<String> trace = new ArrayList<>();
        TracingStore store = new TracingStore(trace);
        Method method = Target.class.getDeclaredMethod("value", String.class);
        var metadata = CacheTestFixtures.metadata(method, "id");
        var cache = new CacheableAspect(store, dev.vertique.cache.config.CacheConfig.defaults(), java.util.Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class));
        AtomicInteger targetCalls = new AtomicInteger();
        MethodInterceptor authorization = invocation -> {
            trace.add("authorize");
            return invocation.proceed();
        };

        Object miss = T011CacheCompositionFixtures.await(Invocations.run(
                this,
                metadata,
                new Object[] {"miss"},
                new MethodInterceptor[] {authorization, cache},
                target(targetCalls, "miss")));
        assertEquals("miss-value-1", miss);
        assertEquals(List.of("authorize", "lookup", "put"), trace);

        trace.clear();
        ResolvedCacheKey hitKey = new ResolvedCacheKey(new CacheRegion("cache", "profiles", 1), "NONE", "hit");
        T011CacheCompositionFixtures.await(store.put(
                hitKey,
                new CacheValueDescriptor(String.class, "vertx"),
                "cached-value",
                java.time.Duration.ofSeconds(60)));
        trace.clear();
        Object hit = T011CacheCompositionFixtures.await(Invocations.run(
                this,
                metadata,
                new Object[] {"hit"},
                new MethodInterceptor[] {authorization, cache},
                target(targetCalls, "hit")));
        assertEquals("cached-value", hit);
        assertEquals(List.of("authorize", "lookup"), trace);
        assertEquals(1, targetCalls.get(), "the cache hit must skip the target after authorization");

        trace.clear();
        AtomicInteger deniedTargetCalls = new AtomicInteger();
        MethodInterceptor deny = invocation -> {
            trace.add("authorize");
            return Future.failedFuture("forbidden");
        };
        assertThrows(CompletionException.class, () -> Invocations.run(
                        this,
                        metadata,
                        new Object[] {"hit"},
                        new MethodInterceptor[] {deny, cache},
                        target(deniedTargetCalls, "should-not-run"))
                .toCompletionStage()
                .toCompletableFuture()
                .join());
        assertEquals(List.of("authorize"), trace);
        assertEquals(0, deniedTargetCalls.get(), "the denied hit must not invoke the target");

        Aspect aspect = Cacheable.class.getAnnotation(Aspect.class);
        assertEquals(200, aspect.ordering(), "cache must remain inside a higher-ordered security boundary");
    }

    private static Supplier<Future<Object>> target(AtomicInteger calls, String value) {
        return () -> Future.succeededFuture(value + "-value-" + calls.incrementAndGet());
    }

    private static final class TracingStore implements CacheStore {
        private final List<String> trace;
        private final Map<String, Object> values = new HashMap<>();

        private TracingStore(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value) {
            trace.add("lookup");
            return Future.succeededFuture(Optional.ofNullable(values.get(key.canonical())));
        }

        @Override
        public Future<Void> put(
                ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, java.time.Duration ttl) {
            trace.add("put");
            values.put(key.canonical(), value);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> evict(ResolvedCacheKey key) {
            values.remove(key.canonical());
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> clear(CacheRegion region) {
            values.clear();
            return Future.succeededFuture();
        }
    }

    static final class Target {
        @Cacheable(name = "profiles", key = "{0}")
        String value(String id) {
            return id;
        }
    }
}
