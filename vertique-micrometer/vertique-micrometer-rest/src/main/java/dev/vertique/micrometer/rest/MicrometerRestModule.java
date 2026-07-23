// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.interceptor.RequestInterceptor;

/**
 * Dagger module that contributes Micrometer REST server metrics components via multibinding.
 *
 * <p>Install this module alongside {@code RestCoreModule} (or {@code RestModule}) and
 * {@link dev.vertique.micrometer.MicrometerModule} in the application's Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     RestModule.class,
 *     MicrometerModule.class,
 *     MicrometerRestModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>This module is observe-only: it contributes metrics listeners and interceptors that record
 * Micrometer meters without modifying the request or response, and without submitting audit records.
 *
 * <p>Zero-overhead when unconfigured: before {@code VertiqueApplication} bootstrap the backing
 * {@link io.micrometer.core.instrument.MeterRegistry} is an empty composite whose recording is a
 * no-op (NFR-TEL-003). When {@code metrics.enabled=false}, both contributed components return
 * immediately without touching the registry.
 *
 * <p>Contributed bindings:
 * <ul>
 *   <li>{@link RestServerRequestMetricsListener} into {@code Set<RestRequestCompletedListener>} —
 *       records a per-request timer on each completed HTTP request</li>
 *   <li>{@link RestServerActiveRequestsInterceptor} into {@code Set<RequestInterceptor>} —
 *       maintains a gauge tracking in-flight HTTP requests</li>
 * </ul>
 *
 * <p>{@link #metricsConfig()} declares a {@code @BindsOptionalOf} for {@link MetricsConfig} so
 * that {@link RestServerRequestMetricsListener} and {@link RestServerActiveRequestsInterceptor} can
 * inject {@code Optional<MetricsConfig>} without requiring
 * {@link dev.vertique.micrometer.MicrometerModule} to be present. When
 * {@code MicrometerModule} is also installed its {@code @Provides MetricsConfig} satisfies the
 * optional binding; when it is absent the optional is empty and both components default to enabled.
 */
@Module
public abstract class MicrometerRestModule {

    private MicrometerRestModule() {}

    // --- Optional binding ---

    /**
     * Declares {@link MetricsConfig} as an optional binding.
     *
     * <p>This allows the two contributed components to inject {@code Optional<MetricsConfig>}
     * without requiring {@link dev.vertique.micrometer.MicrometerModule} to be installed. When
     * {@code MicrometerModule} is also present its {@code @Provides MetricsConfig} method satisfies
     * the optional; when absent the optional is empty and both components default to enabled.
     *
     * @return declared; never called directly
     */
    @BindsOptionalOf
    abstract MetricsConfig metricsConfig();

    // --- Multibinding contributions ---

    /**
     * Contributes {@link RestServerRequestMetricsListener} into the
     * {@link RestRequestCompletedListener} multibinding set.
     *
     * <p>The listener records a per-request timer ({@value RestServerRequestMetricsListener#METER_NAME})
     * on each completed HTTP request.
     *
     * @param l the singleton listener; provided by Dagger via its {@code @Inject} constructor
     * @return the listener cast to the SPI type
     */
    @Provides
    @IntoSet
    static RestRequestCompletedListener restServerRequestMetrics(RestServerRequestMetricsListener l) {
        return l;
    }

    /**
     * Contributes {@link RestServerActiveRequestsInterceptor} into the
     * {@link RequestInterceptor} multibinding set.
     *
     * <p>The interceptor maintains a gauge ({@value RestServerActiveRequestsInterceptor#METER_NAME})
     * tracking in-flight HTTP requests.
     *
     * @param i the singleton interceptor; provided by Dagger via its {@code @Inject} constructor
     * @return the interceptor cast to the SPI type
     */
    @Provides
    @IntoSet
    static RequestInterceptor restServerActiveRequests(RestServerActiveRequestsInterceptor i) {
        return i;
    }
}
