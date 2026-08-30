// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.services;

import dagger.BindsOptionalOf;
import dagger.Module;
import dev.vertique.micrometer.MetricsConfig;

/**
 * Dagger module that contributes Micrometer service dispatch metrics components via multibinding.
 *
 * <p>Install this module alongside {@link dev.vertique.services.DispatchModule} and
 * {@link dev.vertique.micrometer.MicrometerModule} in the application's Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     DispatchModule.class,
 *     MicrometerModule.class,
 *     MicrometerServicesModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>This module is observe-only: it contributes a {@link ServiceInterceptor} that records
 * Micrometer meters for service dispatch operations without modifying the dispatch outcome,
 * and without submitting audit records.
 *
 * <p>Zero-overhead when unconfigured: before {@code VertiqueApplication} bootstrap the backing
 * {@link io.micrometer.core.instrument.MeterRegistry} is an empty composite whose recording is a
 * no-op (NFR-TEL-003). When {@code metrics.enabled=false}, the contributed interceptor returns
 * immediately without touching the registry.
 *
 * <p>Contributed bindings:
 * <ul>
 *   <li>{@link ServiceDispatchMetricsInterceptor} into {@code Set<ServiceInterceptor>} —
 *       records a per-dispatch timer on each terminal service outcome</li>
 * </ul>
 *
 * <p>{@link #metricsConfig()} declares a {@code @BindsOptionalOf} for {@link MetricsConfig} so
 * that {@link ServiceDispatchMetricsInterceptor} can inject {@code Optional<MetricsConfig>} without
 * requiring {@link dev.vertique.micrometer.MicrometerModule} to be present. When
 * {@code MicrometerModule} is also installed its {@code @Provides MetricsConfig} satisfies the
 * optional binding; when it is absent the optional is empty and the interceptor defaults to enabled.
 */
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class MicrometerServicesModule {

    private MicrometerServicesModule() {}

    // --- Optional binding ---

    /**
     * Declares {@link MetricsConfig} as an optional binding.
     *
     * <p>This allows {@link ServiceDispatchMetricsInterceptor} to inject
     * {@code Optional<MetricsConfig>} without requiring
     * {@link dev.vertique.micrometer.MicrometerModule} to be installed. When
     * {@code MicrometerModule} is also present its {@code @Provides MetricsConfig} satisfies the
     * optional; when absent the optional is empty and the interceptor defaults to enabled.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();

}
