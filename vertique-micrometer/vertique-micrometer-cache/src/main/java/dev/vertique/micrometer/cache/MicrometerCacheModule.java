// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.cache;

import dagger.BindsOptionalOf;
import dagger.Module;
import dev.vertique.micrometer.MetricsConfig;

/**
 * Dagger module that contributes Micrometer cache metrics through the cache observer SPI.
 *
 * <p>Install this module alongside the cache core module and
 * {@link dev.vertique.micrometer.MicrometerModule} in the application's Dagger component.
 * The optional metrics configuration defaults to enabled when the core Micrometer module is not
 * installed.
 */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class MicrometerCacheModule {

    private MicrometerCacheModule() {}

    /**
     * Declares {@link MetricsConfig} as an optional binding for the cache observer.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();
}
