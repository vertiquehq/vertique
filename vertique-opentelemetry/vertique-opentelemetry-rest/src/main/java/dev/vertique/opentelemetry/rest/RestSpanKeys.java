// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Package-private constants for routing-context data keys and custom OpenTelemetry
 * {@link AttributeKey}s used by the REST span enrichment components.
 *
 * <p>This class is package-private and not part of the public API of this module.
 */
final class RestSpanKeys {

    // --- Routing-context data keys ---

    /**
     * Key under which the active server {@link io.opentelemetry.api.trace.Span} is stored in
     * {@link io.vertx.ext.web.RoutingContext#data()} by
     * {@link ServerSpanEnrichmentContributor}.
     *
     * <p>Downstream components (e.g. {@link ServerSpanOutcomeInterceptor}) retrieve the span
     * from this key to avoid the overhead of {@link io.opentelemetry.api.trace.Span#current()}.
     */
    static final String SPAN_KEY = "dev.vertique.opentelemetry.rest.serverSpan";

    // --- Custom attribute keys ---

    /**
     * OpenTelemetry {@link AttributeKey} for the Vertique operationId attribute, recorded on the
     * server span by {@link ServerSpanEnrichmentContributor}.
     *
     * <p>Value is the OpenAPI {@code operationId} string (e.g. {@code "orders.list"}).
     */
    static final AttributeKey<String> VERTIQUE_OPERATION_ID = AttributeKey.stringKey("vertique.operation.id");

    private RestSpanKeys() {}
}
