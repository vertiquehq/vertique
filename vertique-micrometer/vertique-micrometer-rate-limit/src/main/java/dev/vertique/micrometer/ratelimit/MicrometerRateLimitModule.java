// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.ratelimit;

import dagger.BindsOptionalOf;
import dagger.Module;
import dev.vertique.micrometer.MetricsConfig;

/** Dagger bindings for the optional Micrometer rate-limit observer. */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class MicrometerRateLimitModule {

    private MicrometerRateLimitModule() {}

    /** Declares the optional global metrics configuration used by this adapter. */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();
}
