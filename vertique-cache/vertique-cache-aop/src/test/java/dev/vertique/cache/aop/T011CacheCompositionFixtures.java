// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import dagger.Module;
import dagger.Provides;
import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.Invocations;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.event.CacheEvent;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Neutral T011 fixtures published from cache-core for provider composition tests. */
public final class T011CacheCompositionFixtures {

    private T011CacheCompositionFixtures() {}

    /** Returns a deterministic cache configuration for one selected placement mode. */
    public static CacheConfig config(CacheMode mode, boolean enabled) {
        return new CacheConfig(enabled, mode, 60, 86_400, "system", 1_024, 1_048_576, 100, 100, Map.of());
    }

    /** Awaits a Vert.x future with a bounded test timeout. */
    public static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    /** Captures neutral observations without exposing keys, arguments, or payloads. */
    public static final class RecordingObserver implements CacheObserver {
        private final List<CacheOperationCompleted> observations = new ArrayList<>();

        @Inject
        public RecordingObserver() {}

        @Override
        public void onEvent(CacheEvent event) {
            if (event instanceof CacheOperationCompleted completed) {
                observations.add(completed);
            }
        }

        public List<CacheOperationCompleted> observations() {
            return List.copyOf(observations);
        }
    }

    /** Supplies the controlled application configuration used by Dagger graph tests. */
    @Module
    public static final class ConfigModule {
        private final JsonObject config;

        public ConfigModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @Singleton
        @VertxConfig
        public JsonObject vertxConfig() {
            return config;
        }
    }

    /** A Dagger-managed bean with local and clustered cacheable methods. */
    public static class CacheableService {
        private final AtomicInteger localCalls = new AtomicInteger();
        private final AtomicInteger clusteredCalls = new AtomicInteger();

        @Inject
        public CacheableService() {}

        @Cacheable(name = "profiles", key = "0", mode = CacheMode.LOCAL, subject = CacheIdentity.NONE)
        public Future<String> local(String ignoredKey) {
            return Future.succeededFuture("local-business-result-" + localCalls.incrementAndGet());
        }

        @Cacheable(name = "clustered-profiles", key = "0", mode = CacheMode.CLUSTERED, subject = CacheIdentity.NONE)
        public Future<String> clustered(String ignoredKey) {
            return Future.succeededFuture("clustered-business-result-" + clusteredCalls.incrementAndGet());
        }

        public int localCalls() {
            return localCalls.get();
        }

        public int clusteredCalls() {
            return clusteredCalls.get();
        }
    }

    /** Hand-written equivalent of the generated AOP proxy used by composition tests. */
    public static final class CacheableService$AopProxy extends CacheableService {
        private static final MethodMetadata LOCAL_METADATA = metadata("local");
        private static final MethodMetadata CLUSTERED_METADATA = metadata("clustered");

        private final MethodInterceptor localInterceptor;
        private final MethodInterceptor clusteredInterceptor;

        @Inject
        public CacheableService$AopProxy(AspectProvider<Cacheable> provider) {
            localInterceptor = provider.interceptor(LOCAL_METADATA, annotation("local"));
            clusteredInterceptor = provider.interceptor(CLUSTERED_METADATA, annotation("clustered"));
        }

        @Override
        public Future<String> local(String key) {
            return invoke(LOCAL_METADATA, localInterceptor, () -> super.local(key));
        }

        @Override
        public Future<String> clustered(String key) {
            return invoke(CLUSTERED_METADATA, clusteredInterceptor, () -> super.clustered(key));
        }

        private Future<String> invoke(
                MethodMetadata metadata,
                MethodInterceptor interceptor,
                java.util.function.Supplier<Future<String>> call) {
            Future<Object> result = Invocations.run(
                    this,
                    metadata,
                    new Object[] {"stable-selector"},
                    new MethodInterceptor[] {interceptor},
                    () -> call.get().map(value -> (Object) value));
            return result.map(value -> (String) value);
        }

        private static MethodMetadata metadata(String name) {
            try {
                Method method = CacheableService.class.getDeclaredMethod(name, String.class);
                return new ReflectiveMethodMetadata(method, List.of(parameterMetadata()));
            } catch (NoSuchMethodException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static ParameterMetadata parameterMetadata() {
            return new ParameterMetadata() {
                @Override
                public int index() {
                    return 0;
                }

                @Override
                public String name() {
                    return "ignoredKey";
                }

                @Override
                public Class<?> type() {
                    return String.class;
                }

                @Override
                public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
                    return Optional.empty();
                }

                @Override
                public boolean hasAnnotation(Class<? extends Annotation> type) {
                    return false;
                }

                @Override
                public Type genericType() {
                    return String.class;
                }
            };
        }

        private static Cacheable annotation(String methodName) {
            try {
                return CacheableService.class
                        .getDeclaredMethod(methodName, String.class)
                        .getAnnotation(Cacheable.class);
            } catch (NoSuchMethodException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
