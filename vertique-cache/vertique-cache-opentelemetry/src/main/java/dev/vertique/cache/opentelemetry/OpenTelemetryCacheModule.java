// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.opentelemetry;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.spi.CacheObserver;

/** Dagger bindings for the optional OpenTelemetry cache observer. */
@Module
public abstract class OpenTelemetryCacheModule {

    private OpenTelemetryCacheModule() {}

    /** Contributes the cache tracing observer to the provider-neutral cache observer set. */
    @Binds
    @IntoSet
    abstract CacheObserver cacheTracingObserver(CacheTracingObserver observer);
}
