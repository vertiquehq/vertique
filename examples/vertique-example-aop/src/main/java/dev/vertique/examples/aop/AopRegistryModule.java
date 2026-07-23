// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import dagger.Module;
import dagger.Provides;
import dev.vertique.micrometer.MetricsConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Singleton;

/**
 * Concrete Dagger module supplying a caller-owned, deterministic {@link SimpleMeterRegistry} as the
 * application {@link MeterRegistry}, together with an enabled {@link MetricsConfig}.
 *
 * <p>This is the example-local, test-readable analog of the framework's {@code MicrometerModule}: by
 * holding the exact {@link SimpleMeterRegistry} instance the test constructs, the test can read the
 * recorded {@code Timer}s directly off that registry — deterministically — instead of going through
 * the global {@code MeterRegistryHolder} composite.
 *
 * <p>The {@code AspectProvider<Timed>} binding and the {@code @BindsOptionalOf MetricsConfig}
 * declaration live in the abstract sibling {@link AopBindingsModule}; both are installed in the same
 * {@link GreeterComponent}.
 */
@Module
public final class AopRegistryModule {

    private final SimpleMeterRegistry registry;

    /**
     * Creates the module with the caller-owned registry the test will later read timers from.
     *
     * @param registry the deterministic registry instance; must not be {@code null}
     */
    public AopRegistryModule(SimpleMeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Provides the caller-owned {@link SimpleMeterRegistry} as the application {@link MeterRegistry}.
     *
     * @return the registry instance supplied at construction; never {@code null}
     */
    @Provides
    @Singleton
    MeterRegistry meterRegistry() {
        return registry;
    }

    /**
     * Provides an enabled {@link MetricsConfig} so {@code TimedAspect} records timers.
     *
     * @return a default (enabled) metrics configuration; never {@code null}
     */
    @Provides
    @Singleton
    MetricsConfig metricsConfig() {
        return MetricsConfig.builder().build();
    }
}
