// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.aop.Aspect;
import dev.vertique.aop.Invocations;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Proves the cache boundary remains inside an already-authorized invocation chain. */
class CacheSecurityIntegrationTest {

    @Test
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

        Object miss = Invocations.run(
                        this,
                        metadata,
                        new Object[] {"miss"},
                        new MethodInterceptor[] {authorization, cache},
                        target(targetCalls, "miss"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertEquals("miss-value-1", miss);
        assertEquals(List.of("authorize", "lookup", "put"), trace);

        trace.clear();
        CacheKey hitKey = new CacheKey(new CacheRegion("cache", "profiles", 1), "NONE", "hit");
        store.put(hitKey, "cached-value", String.class, java.time.Duration.ofSeconds(60)).join();
        trace.clear();
        Object hit = Invocations.run(
                        this,
                        metadata,
                        new Object[] {"hit"},
                        new MethodInterceptor[] {authorization, cache},
                        target(targetCalls, "hit"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        assertEquals("cached-value", hit);
        assertEquals(List.of("authorize", "lookup"), trace);
        assertEquals(1, targetCalls.get(), "the cache hit must skip the target after authorization");

        trace.clear();
        int getsBeforeDenied = store.getCalls;
        AtomicInteger deniedTargetCalls = new AtomicInteger();
        MethodInterceptor deny = invocation -> {
            trace.add("authorize");
            return Future.failedFuture("forbidden");
        };
        assertThrows(
                CompletionException.class,
                () -> Invocations.run(
                                this,
                                metadata,
                                new Object[] {"hit"},
                                new MethodInterceptor[] {deny, cache},
                                target(deniedTargetCalls, "should-not-run"))
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        assertEquals(List.of("authorize"), trace);
        assertEquals(getsBeforeDenied, store.getCalls, "the denied hit must not reach CacheStore.get");
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
        private int getCalls;

        private TracingStore(List<String> trace) {
            this.trace = trace;
        }

        @Override
        public Future<Optional<Object>> get(CacheKey key, Type declaredType) {
            getCalls++;
            trace.add("lookup");
            return Future.succeededFuture(Optional.ofNullable(values.get(key.canonical())));
        }

        @Override
        public Future<Void> put(CacheKey key, Object value, Type declaredType, java.time.Duration ttl) {
            trace.add("put");
            values.put(key.canonical(), value);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> evict(CacheKey key) {
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
