// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Event fired after an authorization policy has produced a decision.
 *
 * <p>Carries both the {@link AuthorizationRequest} (who asked, what action, which resource) and
 * the {@link AuthorizationDecision} (permitted or denied, reason code, policy metadata).
 * Observers may use this event for audit logging, anomaly detection, or metrics.
 *
 * <p>All fields are non-null after construction; the {@code origin} optional carries the
 * {@link RequestOrigin} when network-envelope information was captured before authorization.
 *
 * @param occurredAt wall-clock instant when the decision was reached; never null
 * @param correlation correlation context for the request that triggered this event; never null
 * @param origin      captured network-envelope facts; non-null {@link Optional} — use
 *                    {@link Optional#empty()} when origin was not captured
 * @param request     the authorization request that was evaluated; never null
 * @param decision    the policy decision that was produced; never null
 */
public record AuthorizationDecisionEvent(
        Instant occurredAt,
        CorrelationContext correlation,
        Optional<RequestOrigin> origin,
        AuthorizationRequest request,
        AuthorizationDecision decision) {

    /**
     * Compact constructor — validates that all fields are non-null.
     */
    public AuthorizationDecisionEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decision, "decision");
    }

    /**
     * Derives the {@link ReconstructedAuthorityMode} that governed this decision, or
     * {@link Optional#empty()} when neither an explicit stamp nor an intrinsic reconstruction
     * marker is present.
     *
     * <p>Resolution order:
     * <ol>
     *   <li><strong>Explicit stamp</strong> — {@link #decision()}'s
     *       {@link AuthorizationDecision#safeAttributes()} carrying
     *       {@link ReconstructedAuthorityMode#DECISION_ATTRIBUTE}, parsed as the enum name. This is
     *       the only source for {@link ReconstructedAuthorityMode#LIVE_RESOLVED}, which is an
     *       evaluation outcome a decorator stamps — never a marker value. An unrecognized stamp
     *       value is treated as absent (returns {@link Optional#empty()} rather than throwing).</li>
     *   <li><strong>Intrinsic marker fallback</strong> — when no stamp is present, the mode
     *       intrinsic to {@link #request()}'s {@link AuthorizationRequest#securityContext()}
     *       reconstruction marker, covering {@link ReconstructedAuthorityMode#CAPTURED} and
     *       {@link ReconstructedAuthorityMode#ATTRIBUTION_ONLY} contexts that no decorator
     *       stamped.</li>
     * </ol>
     *
     * <p>A non-reconstructed request with no stamp yields {@link Optional#empty()}.
     *
     * @return the derived authority mode, or {@link Optional#empty()} when neither source applies
     */
    public Optional<ReconstructedAuthorityMode> authorityMode() {
        Object stamped = decision.safeAttributes().get(ReconstructedAuthorityMode.DECISION_ATTRIBUTE);
        if (stamped instanceof String stampedName) {
            try {
                return Optional.of(ReconstructedAuthorityMode.valueOf(stampedName));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return request.securityContext().reconstruction().map(ReconstructionMarker::mode);
    }

    /**
     * Surfaces the embedded {@link #request()}'s {@link AuthorizationRequest#origin()} — the
     * transport-neutral invocation boundary the request was raised through — for audit projections.
     *
     * @return the invocation origin carried by {@link #request()}; never {@code null}
     */
    public InvocationOrigin invocationOrigin() {
        return request.origin();
    }
}
