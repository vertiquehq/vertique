// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.AuthorizationRequest;
import io.vertx.core.Future;
import java.util.Objects;

/**
 * {@link AuthorizationDecisionPoint} adapter that wraps a synchronous {@link AuthorizationPolicy}.
 *
 * <p>Used when an application provides a sync {@link AuthorizationPolicy} core SPI binding but no
 * async {@link AuthorizationDecisionPoint} override. The sync policy is called on the Vert.x event
 * loop; it MUST NOT block. The result is wrapped in {@link Future#succeededFuture}.
 *
 * <p>If the policy throws a {@link RuntimeException} the exception is caught and re-wrapped as a
 * failed {@link Future} so the Vert.x async pipeline handles it properly rather than aborting the
 * event loop.
 *
 * <p>This is a <strong>pure evaluator</strong>: {@link #decide(AuthorizationRequest)} returns the
 * {@link AuthorizationDecision} and emits nothing. The enforcement layer
 * ({@link SecurityPolicyEnforcer}) emits exactly one
 * {@link dev.vertique.security.events.AuthorizationDecisionEvent} per authorization attempt
 * (ADR-0114).
 *
 * @see AuthorizationDecisionPoint
 * @see VertxProviderDecisionPoint
 */
public final class SyncPolicyDecisionPoint implements AuthorizationDecisionPoint {

    private final AuthorizationPolicy policy;

    /**
     * Creates a new adapter.
     *
     * @param policy the synchronous authorization policy to delegate to; must not be {@code null}
     */
    public SyncPolicyDecisionPoint(AuthorizationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    // --- AuthorizationDecisionPoint ---

    /**
     * Delegates to the wrapped sync {@link AuthorizationPolicy} and returns the decision
     * asynchronously.
     *
     * <p>If the policy throws a {@link RuntimeException} the future fails with that exception.
     * Pure evaluator: this method emits no event — the enforcement layer
     * ({@link SecurityPolicyEnforcer}) owns emission (ADR-0114).
     *
     * @param request the authorization request; must not be {@code null}
     * @return a {@link Future} completing with the authorization decision, or a failed future if the
     *         policy throws
     */
    @Override
    public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");

        AuthorizationDecision decision;
        try {
            decision = policy.decide(request);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        return Future.succeededFuture(decision);
    }
}
