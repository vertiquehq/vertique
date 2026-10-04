// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.aop.AspectProvider;
import dev.vertique.cache.CacheAdapterSupport;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.aop.CacheTestFixtures.RecordingStore;
import dev.vertique.cache.config.CacheConfig;
import dev.vertique.core.codegen.MethodMetadata;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wiring test for {@link CacheAopModule}: the module binds {@code AspectProvider} contributions for
 * {@link Cacheable}, {@link CacheEvict}, and {@link CacheEvict.List}, each constructed through the
 * already-bound {@link CacheAdapterSupport}.
 */
class CacheAopModuleTest {

    @Test
    @DisplayName("binds AspectProvider<Cacheable> to the CacheableAspect singleton")
    void bindsAspectProviderForCacheable() {
        TestComponent component = component(new RecordingStore());

        AspectProvider<Cacheable> provider = component.cacheableAspect();

        assertInstanceOf(CacheableAspect.class, provider);
        assertSame(provider, component.cacheableAspect());
    }

    @Test
    @DisplayName("binds AspectProvider<CacheEvict> to the CacheEvictAspect singleton")
    void bindsAspectProviderForCacheEvict() {
        TestComponent component = component(new RecordingStore());

        AspectProvider<CacheEvict> provider = component.cacheEvictAspect();

        assertInstanceOf(CacheEvictAspect.class, provider);
        assertSame(provider, component.cacheEvictAspect());
    }

    @Test
    @DisplayName("binds AspectProvider<CacheEvict.List> to the CacheEvictListAspect singleton")
    void bindsAspectProviderForCacheEvictList() {
        TestComponent component = component(new RecordingStore());

        AspectProvider<CacheEvict.List> provider = component.cacheEvictListAspect();

        assertInstanceOf(CacheEvictListAspect.class, provider);
        assertSame(provider, component.cacheEvictListAspect());
    }

    @Test
    @DisplayName("bound @Cacheable aspect delegates to the CacheAdapterSupport store")
    void boundCacheableAspectDelegatesToAdapterSupport() throws NoSuchMethodException {
        RecordingStore store = new RecordingStore();
        TestComponent component = component(store);
        Method method = Target.class.getDeclaredMethod("value", String.class);
        MethodMetadata metadata = CacheTestFixtures.metadata(method, "user");

        var interceptor = component.cacheableAspect().interceptor(metadata, method.getAnnotation(Cacheable.class));
        interceptor
                .intercept(CacheTestFixtures.invocation(metadata, new Object[] {"alice"}, new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(1, store.getCalls);
        assertEquals(1, store.putCalls);
    }

    @Test
    @DisplayName("bound @CacheEvict aspect delegates to the CacheAdapterSupport store")
    void boundCacheEvictAspectDelegatesToAdapterSupport() throws NoSuchMethodException {
        RecordingStore store = new RecordingStore();
        TestComponent component = component(store);
        Method method = Target.class.getDeclaredMethod("clearAll");
        MethodMetadata metadata = CacheTestFixtures.metadata(method, "unused");

        var interceptor = component.cacheEvictAspect().interceptor(metadata, method.getAnnotation(CacheEvict.class));
        interceptor
                .intercept(CacheTestFixtures.invocation(metadata, new Object[0], new AtomicInteger()))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertEquals(1, store.clearCalls);
    }

    private static TestComponent component(RecordingStore store) {
        return DaggerCacheAopModuleTest_TestComponent.builder()
                .testRuntimeModule(new TestRuntimeModule(store))
                .build();
    }

    @Singleton
    @Component(modules = {CacheAopModule.class, TestRuntimeModule.class})
    interface TestComponent {

        AspectProvider<Cacheable> cacheableAspect();

        AspectProvider<CacheEvict> cacheEvictAspect();

        AspectProvider<CacheEvict.List> cacheEvictListAspect();
    }

    /** Supplies the {@link CacheAdapterSupport} runtime that the cache core module provides in applications. */
    @Module
    static final class TestRuntimeModule {

        private final RecordingStore store;

        TestRuntimeModule(RecordingStore store) {
            this.store = store;
        }

        @Provides
        @Singleton
        CacheAdapterSupport adapterSupport() {
            return CacheAdapterSupport.forStore(store, CacheConfig.defaults(), Set.of(), Set.of());
        }
    }

    /** Fixture carrying cache-annotated methods. */
    static final class Target {
        @Cacheable(name = "wiring", key = "0", subject = CacheIdentity.NONE)
        String value(String user) {
            return "unused";
        }

        @CacheEvict(name = "wiring", clear = true)
        String clearAll() {
            return "unused";
        }
    }
}
