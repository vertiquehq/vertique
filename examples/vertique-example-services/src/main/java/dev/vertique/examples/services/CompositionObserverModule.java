// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.resilience.spi.ResilienceObserver;

/** Test-only observer multibindings used by the composition characterization. */
@Module
public abstract class CompositionObserverModule {

    /**
     * Registers the cache observer against the shared sequence.
     *
     * @param collector shared sequence
     * @return cache observer
     */
    @Provides
    @IntoSet
    static CacheObserver cacheObserver(CompositionEventCollector collector) {
        return collector::record;
    }

    /**
     * Registers the rate-limit observer against the shared sequence.
     *
     * @param collector shared sequence
     * @return rate-limit observer
     */
    @Provides
    @IntoSet
    static RateLimitObserver rateLimitObserver(CompositionEventCollector collector) {
        return collector::record;
    }

    /**
     * Registers the resilience observer against the shared sequence.
     *
     * @param collector shared sequence
     * @return resilience observer
     */
    @Provides
    @IntoSet
    static ResilienceObserver resilienceObserver(CompositionEventCollector collector) {
        return collector::record;
    }
}
