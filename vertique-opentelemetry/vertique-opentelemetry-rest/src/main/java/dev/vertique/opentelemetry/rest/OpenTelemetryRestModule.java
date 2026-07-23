// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.events.RequestCompletionScope;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.router.OperationHandlerContributor;

/**
 * Dagger module that contributes OpenTelemetry REST span enrichment components via multibinding.
 *
 * <p>Install this module alongside {@code RestCoreModule} (or {@code RestModule}) in the
 * application's Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     RestModule.class,
 *     OpenTelemetryRestModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>This module is observe-only: it contributes span enrichment components that annotate active
 * OpenTelemetry spans without modifying the HTTP request or response, and without submitting
 * audit records.
 *
 * <p>Enrichment is a guaranteed no-op without an OpenTelemetry SDK present — the module uses the
 * OpenTelemetry API only. No configuration gate is provided by design: when no recording span is
 * active, all span operations resolve to the API's built-in no-op implementation.
 *
 * <p>Contributed bindings:
 * <ul>
 *   <li>{@link ServerSpanEnrichmentContributor} into {@code Set<OperationHandlerContributor>} —
 *       per-operation handler that renames the active span and records route/operationId attributes
 *       at priority {@value ServerSpanEnrichmentContributor#PRIORITY}</li>
 *   <li>{@link ServerSpanOutcomeInterceptor} into {@code Set<RequestInterceptor>} —
 *       response interceptor that records span status and {@code error.type} after each response</li>
 *   <li>{@link ServerSpanCompletionScope} as {@link RequestCompletionScope} —
 *       re-establishes the server span as current during the completion-listener dispatch loop so
 *       that Micrometer exemplar samplers can attach a {@code trace_id} to timer samples
 *       (e.g. {@code vertique.rest.server.requests})</li>
 * </ul>
 *
 * @see ServerSpanEnrichmentContributor
 * @see ServerSpanOutcomeInterceptor
 * @see ServerSpanCompletionScope
 * @see RequestCompletionScope
 */
@Module
public abstract class OpenTelemetryRestModule {

    private OpenTelemetryRestModule() {}

    // --- Multibinding contributions ---

    /**
     * Contributes {@link ServerSpanEnrichmentContributor} into the
     * {@link OperationHandlerContributor} multibinding set.
     *
     * <p>The contributor adds a per-request handler that enriches the active OpenTelemetry span
     * with HTTP route and operationId attributes.
     *
     * @param contributor the singleton contributor; provided by Dagger via its {@code @Inject} ctor
     * @return the contributor cast to the SPI type
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor serverSpanEnrichment(ServerSpanEnrichmentContributor contributor) {
        return contributor;
    }

    /**
     * Contributes {@link ServerSpanOutcomeInterceptor} into the {@link RequestInterceptor}
     * multibinding set.
     *
     * <p>The interceptor records span outcome (status code and error type) after each HTTP response.
     *
     * @param interceptor the singleton interceptor; provided by Dagger via its {@code @Inject} ctor
     * @return the interceptor cast to the SPI type
     */
    @Provides
    @IntoSet
    static RequestInterceptor serverSpanOutcome(ServerSpanOutcomeInterceptor interceptor) {
        return interceptor;
    }

    /**
     * Contributes {@link ServerSpanCompletionScope} into the {@link RequestCompletionScope}
     * multibinding set.
     *
     * <p>This satisfies the {@code @Multibinds Set<RequestCompletionScope>} declared in
     * {@code RestCoreModule}, enabling the {@link dev.vertique.rest.core.events.RestRequestCompletionEmitter}
     * to re-establish the server span as current during completion-listener dispatch. This allows
     * Micrometer exemplar samplers to attach a {@code trace_id} to timer samples recorded in
     * {@code RestRequestCompletedListener} impls (e.g.
     * {@link dev.vertique.micrometer.rest.RestServerRequestMetricsListener}).
     *
     * @param scope the singleton scope implementation
     * @return the scope contributed to the {@link RequestCompletionScope} set
     */
    @Provides
    @IntoSet
    static RequestCompletionScope serverSpanCompletionScope(ServerSpanCompletionScope scope) {
        return scope;
    }
}
