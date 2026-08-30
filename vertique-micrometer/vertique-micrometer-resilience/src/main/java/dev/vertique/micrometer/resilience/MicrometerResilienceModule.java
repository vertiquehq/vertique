// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.resilience;

import dagger.BindsOptionalOf;
import dagger.Module;
import dev.vertique.micrometer.MetricsConfig;

/** Dagger bindings for the optional Micrometer resilience observer. */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class MicrometerResilienceModule {

    private MicrometerResilienceModule() {}

    /** Declares the optional global metrics configuration used by this adapter. */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();
}
