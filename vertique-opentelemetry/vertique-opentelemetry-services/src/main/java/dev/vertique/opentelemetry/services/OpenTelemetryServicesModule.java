// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.services;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.services.interceptor.ServiceInterceptor;

/**
 * Dagger module that contributes OpenTelemetry service dispatch span enrichment components via
 * multibinding.
 *
 * <p>Install this module alongside {@link dev.vertique.services.DispatchModule} in the
 * application's Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     DispatchModule.class,
 *     OpenTelemetryServicesModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>This module is observe-only: it contributes a {@link ServiceInterceptor} that enriches the
 * active CONSUMER event-bus span without modifying the dispatch outcome, and without submitting
 * audit records.
 *
 * <p>Span lifecycle: no sender-side seam exists in the event bus dispatch pipeline. Spans are
 * enriched only under a traced parent (propagation mode: PROPAGATE). When no recording span is
 * current, all operations are silent no-ops via the OpenTelemetry API's built-in no-op
 * implementation. Terminal-outcome status is best-effort: the reply is sent before
 * {@code onTerminalComplete} fires, so writes to an already-ended span are safe no-ops per the
 * OpenTelemetry API contract.
 *
 * <p>Zero-overhead when tracing is unconfigured: the module uses the OpenTelemetry API only
 * (no SDK in compile scope, per NFR-TEL-002). No configuration gate is provided by design —
 * enrichment is always attempted and silently becomes a no-op when no recording span is present.
 *
 * <p>Contributed bindings:
 * <ul>
 *   <li>{@link ServiceDispatchSpanEnrichmentInterceptor} into {@code Set<ServiceInterceptor>} —
 *       enriches the active CONSUMER span with service target and one-way attributes on dispatch,
 *       and records terminal outcome status on terminal complete</li>
 * </ul>
 *
 * @see ServiceDispatchSpanEnrichmentInterceptor
 */
@Module
public abstract class OpenTelemetryServicesModule {

    private OpenTelemetryServicesModule() {}

    // --- Multibinding contributions ---

    /**
     * Contributes {@link ServiceDispatchSpanEnrichmentInterceptor} into the
     * {@link ServiceInterceptor} multibinding set.
     *
     * <p>The interceptor enriches the active CONSUMER span with service target and one-way
     * attributes on dispatch, and records terminal outcome status on terminal complete.
     *
     * @param interceptor the singleton interceptor; provided by Dagger via its {@code @Inject} ctor
     * @return the interceptor cast to the SPI type
     */
    @Provides
    @IntoSet
    static ServiceInterceptor serviceDispatchSpanEnrichment(ServiceDispatchSpanEnrichmentInterceptor interceptor) {
        return interceptor;
    }
}
