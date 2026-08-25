// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.spi.CacheObserver;

/** Dagger bindings for the optional OpenTelemetry cache observer. */
@Module
public abstract class OpenTelemetryCacheModule {

    private OpenTelemetryCacheModule() {}

    /** Contributes the cache tracing observer to the provider-neutral cache observer set. */
    @Provides
    @IntoSet
    static CacheObserver cacheTracingObserver(CacheTracingObserver observer) {
        return observer;
    }
}
