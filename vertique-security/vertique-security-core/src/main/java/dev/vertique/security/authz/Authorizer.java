// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import dev.vertique.security.SecurityContext;
import io.vertx.core.Future;

/**
 * Transport-neutral authorization engine: it decides whether a requested action is permitted and
 * <strong>returns</strong> the {@link AuthorizationDecision}.
 *
 * <p>An {@code Authorizer} is a <em>pure decision function</em>. It never emits an authorization
 * event and never throws to signal a denial — every outcome (permit, deny, or fail-closed) is
 * reported as a succeeded {@link Future} carrying an {@link AuthorizationDecision} with a stable
 * {@link AuthzReasonCodes reason code}. Event emission is the responsibility of the per-surface
 * enforcement layer (PEP), not the engine (see ADR-0114).
 *
 * <p>The framework default ({@code DefaultAuthorizer}) is a synchronous in-memory function of the
 * actor's roles and the requested action; it always returns an already-completed future.
 * Implementations that consult an asynchronous backend may complete the future later, but must still
 * report failures as a fail-closed deny rather than a failed future.
 */
public interface Authorizer {

    /**
     * Decides whether the given request is permitted.
     *
     * @param request the authorization request to evaluate; must not be {@code null}
     * @return a future carrying the {@link AuthorizationDecision}; never a failed future for a normal
     *     deny — failures fail closed to a denied decision
     * @throws NullPointerException if {@code request} is {@code null}
     */
    Future<AuthorizationDecision> authorize(AuthorizationRequest request);

    /**
     * Action-only convenience overload: decides whether {@code action} is permitted for the principal
     * described by {@code ctx} against {@code resource}, with no extra evaluation-time context.
     *
     * <p>Equivalent to building an {@link AuthorizationRequest} whose {@code action} is
     * {@link ActionRef#value()} and whose context map is empty, then calling {@link #authorize(AuthorizationRequest)}.
     * This overload is also the entry point that may receive a {@code null} {@code ctx}; a conforming
     * implementation fails closed in that case rather than throwing.
     *
     * @param ctx      the security context of the requesting principal; may be {@code null} (fails closed)
     * @param action   the action being requested; must not be {@code null}
     * @param resource reference to the target resource; must not be {@code null}
     * @return a future carrying the {@link AuthorizationDecision}; never a failed future for a normal deny
     * @throws NullPointerException if {@code action} or {@code resource} is {@code null}
     */
    Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource);
}
