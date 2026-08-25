// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.micrometer;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.micrometer.MetricsConfig;

/**
 * Dagger module that contributes Micrometer cache metrics through the cache observer SPI.
 *
 * <p>Install this module alongside the cache core module and
 * {@link dev.vertique.micrometer.MicrometerModule} in the application's Dagger component.
 * The optional metrics configuration defaults to enabled when the core Micrometer module is not
 * installed.
 */
@Module
public abstract class MicrometerCacheModule {

    private MicrometerCacheModule() {}

    /**
     * Declares {@link MetricsConfig} as an optional binding for the cache observer.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();

    /**
     * Contributes {@link CacheMetricsObserver} to the cache observer multibinding set.
     *
     * @param observer the singleton cache metrics observer
     * @return the observer exposed through the cache observer SPI type
     */
    @Binds
    @IntoSet
    abstract CacheObserver cacheMetricsObserver(CacheMetricsObserver observer);
}
