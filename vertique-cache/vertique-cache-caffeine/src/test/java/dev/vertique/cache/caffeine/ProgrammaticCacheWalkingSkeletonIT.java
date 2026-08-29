// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.caffeine;

import static dev.vertique.cache.aop.T011CacheCompositionFixtures.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.Cache;
import dev.vertique.cache.CacheBuilder;
import dev.vertique.cache.CacheIdentity;
import dev.vertique.cache.aop.T011CacheCompositionFixtures;
import dev.vertique.cache.spi.CacheObserver;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Walking proof for the injected programmatic API on a real Caffeine graph. */
class ProgrammaticCacheWalkingSkeletonIT {
    @Test
    void scalarProgrammaticAndAnnotationCallsShareCaffeineExecution() throws Exception {
        LocalComponent component = DaggerProgrammaticCacheWalkingSkeletonIT_LocalComponent.builder()
                .configModule(new T011CacheCompositionFixtures.ConfigModule(new io.vertx.core.json.JsonObject()))
                .build();
        Cache<String, String> cache = component
                .cacheBuilder()
                .cache("programmatic-products", String.class)
                .identity(CacheIdentity.NONE)
                .build();
        AtomicInteger loads = new AtomicInteger();

        assertEquals("product", await(cache.get("p-1", ignored -> {
            loads.incrementAndGet();
            return io.vertx.core.Future.succeededFuture("product");
        })));
        assertEquals("product", await(cache.get("p-1", ignored -> {
            loads.incrementAndGet();
            return io.vertx.core.Future.succeededFuture("unexpected");
        })));
        assertEquals(1, loads.get());
    }

    @Singleton
    @Component(
            modules = {
                CacheCaffeineModule.class,
                dev.vertique.config.parser.ConfigParsingModule.class,
                T011CacheCompositionFixtures.ConfigModule.class,
                ObserverModule.class
            })
    interface LocalComponent {
        CacheBuilder cacheBuilder();
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
