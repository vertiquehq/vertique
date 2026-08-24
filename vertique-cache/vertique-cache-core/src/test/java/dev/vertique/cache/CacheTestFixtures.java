// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import dev.vertique.aop.Invocation;
import dev.vertique.cache.spi.CacheKey;
import dev.vertique.cache.spi.CacheRegion;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

final class CacheTestFixtures {

    private CacheTestFixtures() {}

    static MethodMetadata metadata(Method method, String parameterName) {
        ParameterMetadata parameter = new ParameterMetadata() {
            @Override
            public int index() {
                return 0;
            }

            @Override
            public String name() {
                return parameterName;
            }

            @Override
            public Class<?> type() {
                return method.getParameterTypes()[0];
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
                return method.getGenericParameterTypes()[0];
            }
        };
        return new ReflectiveMethodMetadata(method, List.of(parameter));
    }

    static Invocation invocation(MethodMetadata metadata, Object[] arguments, AtomicInteger targetCalls) {
        return new Invocation() {
            @Override
            public MethodMetadata target() {
                return metadata;
            }

            @Override
            public Object[] arguments() {
                return arguments;
            }

            @Override
            public Object instance() {
                return new Object();
            }

            @Override
            public Future<Object> proceed() {
                return Future.succeededFuture("result-" + targetCalls.incrementAndGet());
            }
        };
    }

    static final class RecordingStore implements CacheStore {
        private final Map<String, Object> values = new java.util.HashMap<>();
        int getCalls;
        int putCalls;
        int evictCalls;
        int clearCalls;
        Duration lastTtl;
        boolean failGets;
        boolean failPuts;
        boolean failEvictions;
        boolean failClear;

        @Override
        public Future<Optional<Object>> get(CacheKey key, Type declaredType) {
            getCalls++;
            if (failGets) {
                return Future.failedFuture("get failed");
            }
            return Future.succeededFuture(Optional.ofNullable(values.get(key.canonical())));
        }

        @Override
        public Future<Void> put(CacheKey key, Object value, Type declaredType, Duration ttl) {
            putCalls++;
            lastTtl = ttl;
            if (failPuts) {
                return Future.failedFuture("put failed");
            }
            values.put(key.canonical(), value);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> evict(CacheKey key) {
            evictCalls++;
            if (failEvictions) {
                return Future.failedFuture("evict failed");
            }
            values.remove(key.canonical());
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> clear(CacheRegion region) {
            clearCalls++;
            if (failClear) {
                return Future.failedFuture("clear failed");
            }
            values.clear();
            return Future.succeededFuture();
        }
    }
}
