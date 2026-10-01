// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.HttpAttributes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OperationHandlerContributor} that enriches the active OpenTelemetry server span with
 * HTTP route and operationId attributes for each REST operation request.
 *
 * <p>Contributed to the {@link OperationHandlerContributor} multibinding by
 * {@link OpenTelemetryRestModule}. Runs at priority {@value #PRIORITY} — one step after
 * {@link dev.vertique.rest.core.events.OperationIdCaptureContributor} at 350, so the operationId
 * is always captured before this handler fires.
 *
 * <p>For each incoming request, the contributed handler:
 * <ol>
 *   <li>Resolves the current active span via {@link Span#current()}</li>
 *   <li>If the span context is valid: stores the span under {@link RestSpanKeys#SPAN_KEY} for
 *       downstream interceptors</li>
 *   <li>If the span is recording: renames it to {@code "METHOD /route/template"} (e.g.
 *       {@code "GET /orders/{id}"}), sets {@code http.route} to the OpenAPI path template,
 *       sets {@code vertique.operation.id} to the operationId, and, when the operation belongs to an
 *       application, sets {@code vertique.application.name} to the operation's application name</li>
 *   <li>Always calls {@code rc.next()} — enrichment failure never breaks the pipeline</li>
 * </ol>
 *
 * <p>The handler runs after the route's authentication handlers. A request rejected before it runs
 * (authentication 401, authorization 403, the {@code @Consumes} check 415, or the validation gate
 * 400) never reaches it and its span carries none of these attributes.
 *
 * <p>This component uses the OpenTelemetry API only — no SDK dependency. When no SDK is installed,
 * all span operations are no-ops and no span is stored on the context.
 *
 * @see OpenTelemetryRestModule
 * @see ServerSpanOutcomeInterceptor
 * @see RestSpanKeys
 */
@Slf4j
@Singleton
@RegisterIntoSet(OperationHandlerContributor.class)
public final class ServerSpanEnrichmentContributor implements OperationHandlerContributor {

    /** Priority for this contributor — one step after {@code OperationIdCaptureContributor} at 350. */
    public static final int PRIORITY = 360;

    /**
     * Constructs the contributor. No dependencies are required; the OpenTelemetry API is accessed
     * via the static {@link Span#current()} method at request time.
     */
    @Inject
    public ServerSpanEnrichmentContributor() {}

    /**
     * Returns the priority of this contributor.
     *
     * @return {@value #PRIORITY}
     */
    @Override
    public int priority() {
        return PRIORITY;
    }

    /**
     * Adds a routing handler that enriches the active OpenTelemetry span with HTTP and operation
     * attributes for the current request.
     *
     * <p>The route template, operationId, and application name are captured once at registration time
     * from the context and closed over in the handler lambda. At request time the handler:
     * <ol>
     *   <li>Resolves {@link Span#current()} and guards on {@link io.opentelemetry.api.trace.SpanContext#isValid()}</li>
     *   <li>Stores the span in the routing context under {@link RestSpanKeys#SPAN_KEY}</li>
     *   <li>If the span {@link Span#isRecording()}: renames it and sets attributes, including
     *       {@code vertique.application.name} set to the operation's application name when the
     *       operation belongs to an application</li>
     *   <li>Always calls {@code rc.next()} outside the try-block so enrichment failure never
     *       breaks the pipeline</li>
     * </ol>
     *
     * @param context the operation registration context providing route metadata and the target route
     */
    @Override
    public void contribute(OperationRegistrationContext context) {
        String operationId = context.operationId();
        String routeTemplate = context.operation().routeTemplate();
        String applicationName = context.operation().applicationName();

        context.route().addHandler(rc -> {
            try {
                Span span = Span.current();
                if (span.getSpanContext().isValid()) {
                    rc.put(RestSpanKeys.SPAN_KEY, span);
                    if (span.isRecording()) {
                        span.updateName(rc.request().method().name() + " " + routeTemplate);
                        if (routeTemplate != null) {
                            span.setAttribute(HttpAttributes.HTTP_ROUTE, routeTemplate);
                        }
                        if (operationId != null) {
                            span.setAttribute(RestSpanKeys.VERTIQUE_OPERATION_ID, operationId);
                        }
                        if (applicationName != null) {
                            span.setAttribute(RestSpanKeys.VERTIQUE_APPLICATION_NAME, applicationName);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Server span enrichment failed: {}", e.getClass().getSimpleName());
            }
            rc.next();
        });
    }
}
