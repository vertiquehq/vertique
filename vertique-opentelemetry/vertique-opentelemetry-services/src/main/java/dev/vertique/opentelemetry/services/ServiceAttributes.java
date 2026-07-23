// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.services;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Package-private constants for custom OpenTelemetry {@link AttributeKey}s used by the service
 * dispatch span enrichment components.
 *
 * <p>This class is package-private and not part of the public API of this module.
 */
final class ServiceAttributes {

    // --- Custom attribute keys ---

    /**
     * OpenTelemetry {@link AttributeKey} for the stable dot-delimited service target id, recorded
     * on the CONSUMER span by {@link ServiceDispatchSpanEnrichmentInterceptor}.
     *
     * <p>Value is the durable target id (e.g. {@code "integration.user-service.get-user"}).
     * Not set when {@link dev.vertique.services.interceptor.ServiceDispatchContext#stableTargetId()}
     * is {@code null}.
     */
    static final AttributeKey<String> SERVICE_TARGET = AttributeKey.stringKey("vertique.service.target");

    /**
     * OpenTelemetry {@link AttributeKey} for the fire-and-forget flag, recorded on the CONSUMER
     * span by {@link ServiceDispatchSpanEnrichmentInterceptor}.
     *
     * <p>Value is {@code true} for one-way ({@code @OneWay}) dispatches, {@code false} for
     * request/reply dispatches.
     */
    static final AttributeKey<Boolean> SERVICE_ONEWAY = AttributeKey.booleanKey("vertique.service.oneway");

    private ServiceAttributes() {}
}
