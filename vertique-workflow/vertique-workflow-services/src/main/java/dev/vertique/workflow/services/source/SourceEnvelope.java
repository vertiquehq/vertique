// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.workflow.ops.SignalProvenance;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.function.Function;

/**
 * The resolved values a transport hands to {@link WorkflowSourceDelivery} with one message.
 *
 * <p><strong>INTERNAL.</strong> This type is an internal seam, outside the maturity promise of
 * {@code vertique-workflow-services}. It is public only because a transport in another repository
 * needs it; do not depend on it from application code.
 *
 * @param transport the transport that carried the message, {@code kafka} or {@code camel}
 * @param security the security context the recording runs under; the actor of the recorded command
 *     comes from it
 * @param identityContributed whether the source's identity function supplied {@code security}
 * @param correlation the correlation to bind for the recording, or {@code null} for none
 * @param origin the invocation origin to bind for the recording
 * @param defaultDedupKey the dedup key to use when the source defines none, or {@code null} for none
 * @param provenance builds the provenance of a signal from the signal's resolved dedup key
 */
public record SourceEnvelope(
        String transport,
        SecurityContext security,
        boolean identityContributed,
        @Nullable CorrelationContext correlation,
        InvocationOrigin origin,
        @Nullable String defaultDedupKey,
        Function<String, SignalProvenance> provenance) {

    /**
     * Validates the components that are required.
     *
     * @throws NullPointerException if {@code transport}, {@code security}, {@code origin} or
     *     {@code provenance} is {@code null}
     */
    public SourceEnvelope {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(provenance, "provenance");
    }
}
