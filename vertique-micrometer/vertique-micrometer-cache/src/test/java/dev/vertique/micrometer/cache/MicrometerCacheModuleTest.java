// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.micrometer.MicrometerModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MicrometerCacheModuleTest {

    @Test
    void contributesCacheMetricsObserverToTheCacheObserverSet() {
        TestComponent component = DaggerMicrometerCacheModuleTest_TestComponent.builder()
                .testConfigModule(new TestConfigModule(new JsonObject()))
                .build();

        Set<CacheObserver> observers = component.cacheObservers();

        assertEquals(1, observers.size());
        assertInstanceOf(CacheMetricsObserver.class, observers.iterator().next());
    }

    @Singleton
    @Component(
            modules = {
                MicrometerModule.class,
                MicrometerCacheModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface TestComponent {
        Set<CacheObserver> cacheObservers();
    }

    @Module
    static final class TestConfigModule {
        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
