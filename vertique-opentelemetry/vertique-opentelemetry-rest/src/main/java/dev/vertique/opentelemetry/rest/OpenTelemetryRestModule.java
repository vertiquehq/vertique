// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import dagger.Module;

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
@Module(includes = GeneratedRegistrationsModule.class)
public abstract class OpenTelemetryRestModule {

    private OpenTelemetryRestModule() {}
}
