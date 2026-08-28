// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.aop.Invocation;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheObservation;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.cache.spi.CacheValueDescriptor;
import dev.vertique.cache.spi.ResolvedCacheKey;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import io.vertx.core.Future;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CacheAspectTest {

    @Test
    void localHitMissAndFailOpen() throws NoSuchMethodException {
        RecordingStore store = new RecordingStore();
        List<CacheObservation> observations = new ArrayList<>();
        CacheObserver observer = observations::add;
        Cacheable annotation =
                Target.class.getDeclaredMethod("value", String.class).getAnnotation(Cacheable.class);
        MethodMetadata metadata = new ReflectiveMethodMetadata(
                Target.class.getDeclaredMethod("value", String.class), List.of(parameterMetadata()));
        CacheableAspect aspect = new CacheableAspect(store, CacheConfig.defaults(), Set.of(observer));
        var interceptor = aspect.interceptor(metadata, annotation);
        AtomicInteger targetCalls = new AtomicInteger();
        Invocation invocation = invocation(targetCalls, "alice");

        assertEquals(
                "value-1",
                interceptor
                        .intercept(invocation)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        assertEquals(
                "value-1",
                interceptor
                        .intercept(invocation)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        assertEquals(1, targetCalls.get(), "a local hit must skip the target");
        assertEquals(1, store.putCalls);

        store.failGets = true;
        assertEquals(
                "value-2",
                interceptor
                        .intercept(invocation)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        assertEquals(2, targetCalls.get(), "a failed cache get must preserve the business path");
        assertFalse(observations.isEmpty(), "cache failures must be observable");
        assertTrue(observations.stream()
                .anyMatch(observation -> observation.outcome().contains("error")));
    }

    @Test
    void disabledBypassesReadAndWrite() throws NoSuchMethodException {
        RecordingStore store = new RecordingStore();
        var method = Target.class.getDeclaredMethod("value", String.class);
        MethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of(parameterMetadata()));
        CacheConfig config =
                new CacheConfig(false, CacheMode.LOCAL, 60, 86_400, "vertx", 1_024, 1_048_576, 10_000, 100, Map.of());
        AtomicInteger targetCalls = new AtomicInteger();

        Object result = new CacheableAspect(store, config, Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class))
                .intercept(invocation(targetCalls, "alice"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("value-1", result);
        assertEquals(0, store.getCalls);
        assertEquals(0, store.putCalls);
    }

    @Test
    void nullResultIsNotCached() throws NoSuchMethodException {
        RecordingStore store = new RecordingStore();
        var method = Target.class.getDeclaredMethod("value", String.class);
        MethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of(parameterMetadata()));
        AtomicInteger targetCalls = new AtomicInteger();
        Invocation invocation = invocation(targetCalls, "alice", () -> Future.succeededFuture(null));
        var interceptor = new CacheableAspect(store, CacheConfig.defaults(), Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class));

        interceptor
                .intercept(invocation)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        interceptor
                .intercept(invocation)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(2, targetCalls.get());
        assertEquals(0, store.putCalls);
    }

    @Test
    void oversizedKeyFailsOpenWithoutStoreAccess() throws NoSuchMethodException {
        RecordingStore store = new RecordingStore();
        var method = Target.class.getDeclaredMethod("value", String.class);
        MethodMetadata metadata = new ReflectiveMethodMetadata(method, List.of(parameterMetadata()));
        CacheConfig config =
                new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "vertx", 1, 1_048_576, 10_000, 100, Map.of());
        AtomicInteger targetCalls = new AtomicInteger();

        Object result = new CacheableAspect(store, config, Set.of())
                .interceptor(metadata, method.getAnnotation(Cacheable.class))
                .intercept(invocation(targetCalls, "alice"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals("value-1", result);
        assertEquals(0, store.getCalls);
        assertEquals(0, store.putCalls);
    }

    private static Invocation invocation(AtomicInteger targetCalls, String argument) {
        return invocation(targetCalls, argument, () -> Future.succeededFuture("value-" + targetCalls.get()));
    }

    private static Invocation invocation(
            AtomicInteger targetCalls, String argument, Supplier<Future<Object>> downstream) {
        return new Invocation() {
            @Override
            public MethodMetadata target() {
                return null;
            }

            @Override
            public Object[] arguments() {
                return new Object[] {argument};
            }

            @Override
            public Object instance() {
                return new Target();
            }

            @Override
            public Future<Object> proceed() {
                targetCalls.incrementAndGet();
                return downstream.get();
            }
        };
    }

    private static ParameterMetadata parameterMetadata() {
        return new ParameterMetadata() {
            @Override
            public int index() {
                return 0;
            }

            @Override
            public String name() {
                return "user";
            }

            @Override
            public Class<?> type() {
                return String.class;
            }

            @Override
            public <A extends java.lang.annotation.Annotation> Optional<A> findAnnotation(Class<A> type) {
                return Optional.empty();
            }

            @Override
            public boolean hasAnnotation(Class<? extends java.lang.annotation.Annotation> type) {
                return false;
            }

            @Override
            public Type genericType() {
                return String.class;
            }
        };
    }

    private static final class RecordingStore implements CacheStore {
        private final Map<String, Object> values = new HashMap<>();
        private int getCalls;
        private int putCalls;
        private boolean failGets;

        @Override
        public Future<Optional<Object>> get(ResolvedCacheKey key, CacheValueDescriptor value) {
            getCalls++;
            if (failGets) {
                return Future.failedFuture("backend unavailable");
            }
            return Future.succeededFuture(Optional.ofNullable(values.get(key.canonical())));
        }

        @Override
        public Future<Void> put(ResolvedCacheKey key, CacheValueDescriptor descriptor, Object value, Duration ttl) {
            putCalls++;
            values.put(key.canonical(), value);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> evict(ResolvedCacheKey key) {
            values.remove(key.canonical());
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> clear(dev.vertique.cache.spi.CacheRegion region) {
            values.clear();
            return Future.succeededFuture();
        }
    }

    static final class Target {
        @Cacheable(name = "profile", key = "{0}", identity = CacheIdentity.NONE)
        String value(String user) {
            return "unused";
        }
    }
}
