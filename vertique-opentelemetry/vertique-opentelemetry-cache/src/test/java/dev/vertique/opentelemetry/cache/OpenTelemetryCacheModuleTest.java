// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.opentelemetry.TracingConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that the cache-owned OpenTelemetry module contributes its observer through Dagger. */
@DisplayName("OpenTelemetry cache module")
class OpenTelemetryCacheModuleTest {

    @Test
    void contributesCacheTracingObserverToTheCacheObserverSet() {
        Set<CacheObserver> observers =
                DaggerOpenTelemetryCacheModuleTest_TestComponent.create().cacheObservers();

        assertEquals(1, observers.size());
        assertInstanceOf(CacheTracingObserver.class, observers.iterator().next());
    }

    @Singleton
    @Component(modules = {OpenTelemetryCacheModule.class, TestModule.class})
    interface TestComponent {
        Set<CacheObserver> cacheObservers();
    }

    @Module
    static final class TestModule {

        private TestModule() {}

        @Provides
        @Singleton
        static Tracer tracer() {
            return OpenTelemetry.noop().getTracer("test");
        }

        @Provides
        @Singleton
        static TracingConfig tracingConfig() {
            return TracingConfig.builder().build();
        }
    }
}
