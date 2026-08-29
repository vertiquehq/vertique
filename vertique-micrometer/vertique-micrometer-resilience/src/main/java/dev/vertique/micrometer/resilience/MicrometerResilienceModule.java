// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.resilience;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.resilience.spi.ResilienceObserver;

/** Dagger bindings for the optional Micrometer resilience observer. */
@Module
public abstract class MicrometerResilienceModule {

    private MicrometerResilienceModule() {}

    /** Declares the optional global metrics configuration used by this adapter. */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();

    /** Contributes the adapter's single observer to the resilience observer set. */
    @Provides
    @IntoSet
    static ResilienceObserver resilienceMetricsObserver(ResilienceMetricsObserver observer) {
        return observer;
    }
}
