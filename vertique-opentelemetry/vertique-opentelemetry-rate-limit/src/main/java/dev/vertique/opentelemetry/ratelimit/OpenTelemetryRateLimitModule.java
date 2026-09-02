// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.ratelimit;

import dagger.Module;

/** Dagger bindings for the optional OpenTelemetry rate-limit observer. */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class OpenTelemetryRateLimitModule {

    private OpenTelemetryRateLimitModule() {}
}
