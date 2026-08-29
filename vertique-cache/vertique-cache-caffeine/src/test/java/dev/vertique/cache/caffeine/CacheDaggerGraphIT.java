// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import static dev.vertique.cache.aop.T011CacheCompositionFixtures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.CacheMode;
import dev.vertique.cache.aop.CacheAopModule;
import dev.vertique.cache.aop.T011CacheCompositionFixtures;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.cache.spi.CacheStore;
import dev.vertique.config.parser.ConfigParsingModule;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;
import javax.inject.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dagger composition proof for the local cache provider and generated-proxy seam. */
class CacheDaggerGraphIT {

    @Test
    @DisplayName("a generated cache proxy resolves the local Caffeine provider and caches its result")
    void generatedProxyComposesWithLocalProvider() throws Exception {
        LocalComponent component = localComponent();

        T011CacheCompositionFixtures.CacheableService$AopProxy proxy = component.proxy();

        // When: the same cacheable method is invoked twice through the proxy.
        String first = await(proxy.local("raw-selector-is-not-observed"));
        String second = await(proxy.local("raw-selector-is-not-observed"));

        // Then: the business result is cached and the provider is the bounded local selection.
        assertEquals(first, second);
        assertEquals(1, proxy.localCalls(), "a local cache hit must skip the target method");
        assertInstanceOf(
                CaffeineCacheStore.class,
                component.providers().get(CacheMode.LOCAL).get());
        assertTrue(component.observer().observations().stream()
                .allMatch(observation -> Set.of("get", "put").contains(observation.operation())));
        assertTrue(component.observer().observations().stream()
                .allMatch(observation -> Set.of("caffeine").contains(observation.provider())));
        assertFalse(component.observer().observations().toString().contains("raw-selector-is-not-observed"));
    }

    @Test
    @DisplayName("the local generated graph includes cache metadata and the transitive core module")
    void generatedComponentIncludesCacheModulesAndMetadata() {
        LocalComponent component = localComponent();

        // Then: the explicit local composition resolves the cache core map and cache aspect.
        assertEquals(Set.of(CacheMode.LOCAL), component.providers().keySet());
        assertNotNull(component.proxy(), "generated cache metadata must resolve the cache aspect");
    }

    private static LocalComponent localComponent() {
        return DaggerCacheDaggerGraphIT_LocalComponent.builder()
                .configModule(new T011CacheCompositionFixtures.ConfigModule(new io.vertx.core.json.JsonObject()))
                .build();
    }

    @Singleton
    @Component(
            modules = {
                CacheCaffeineModule.class,
                CacheAopModule.class,
                ConfigParsingModule.class,
                T011CacheCompositionFixtures.ConfigModule.class,
                ObserverModule.class
            })
    interface LocalComponent {
        Map<CacheMode, Provider<CacheStore>> providers();

        T011CacheCompositionFixtures.CacheableService$AopProxy proxy();

        T011CacheCompositionFixtures.RecordingObserver observer();
    }

    @Module
    static final class ObserverModule {
        @Provides
        @IntoSet
        static CacheObserver cacheObserver(T011CacheCompositionFixtures.RecordingObserver observer) {
            return observer;
        }
    }
}
