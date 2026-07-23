// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import dev.vertique.core.correlation.ProtocolCorrelationRef;
import io.vertx.core.http.HttpServerRequest;
import java.util.Optional;

/**
 * Escape-hatch SPI for protocol-correlation extraction whose rules cannot be expressed by a
 * single-header {@link ProtocolCorrelationSpec} (Context contribution model level 6).
 *
 * <p>The correlation ingress middleware resolves all registered {@link ProtocolCorrelationSpec}s
 * first (the default declarative path) and then iterates the contributor set. Each contributor
 * inspects the inbound request and may return a fully-resolved
 * {@link ProtocolCorrelationRef}; non-empty results are appended to the live
 * {@link dev.vertique.core.correlation.CorrelationContext} via
 * {@code CorrelationContextMutator.addProtocolCorrelation}.
 *
 * <p>Use this SPI only when the spec abstraction is insufficient — typical examples are
 * correlation values extracted from JWT claims, multi-header composite values, or protocols
 * whose generation requires the inbound request state. Prefer {@link ProtocolCorrelationSpec}
 * for ordinary single-header standards (e.g. FAPI {@code X-FAPI-Interaction-ID}).
 */
public interface ProtocolCorrelationContributor {

    /**
     * Resolves a protocol-correlation ref for the inbound request.
     *
     * @param request the inbound HTTP server request; never {@code null}
     * @return a fully-formed {@link ProtocolCorrelationRef} when this contributor matched the
     *         request; {@link Optional#empty()} when no ref applies
     */
    Optional<ProtocolCorrelationRef> resolve(HttpServerRequest request);
}
