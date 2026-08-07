// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import io.vertx.core.Future;

/**
 * Async authorization decision SPI for the REST layer.
 *
 * <p>An {@code AuthorizationDecisionPoint} receives a fully-populated {@link AuthorizationRequest}
 * and returns a {@link Future} of an {@link AuthorizationDecision}. Implementations are free to
 * evaluate the request synchronously (returning {@link Future#succeededFuture}) or asynchronously
 * (e.g., delegating to a remote policy decision point).
 *
 * <p>A decision point is a <strong>pure evaluator</strong>: it returns a decision and emits no
 * event. The enforcement layer ({@link SecurityPolicyEnforcer}) emits exactly one
 * {@link dev.vertique.security.events.AuthorizationDecisionEvent} per authorization attempt,
 * composing every predicate (role/scope + action) into that single event (ADR-0114).
 *
 * <p><strong>Migration note.</strong> An earlier contract (AC-SE-5) required every decision point to
 * emit its own event. That contract is superseded by ADR-0114: app-provided decision points that
 * emit today MUST stop emitting, otherwise an authorization attempt produces duplicate events.
 *
 * <p>The framework provides two default implementations:
 * <ul>
 *   <li>{@link VertxProviderDecisionPoint} — wraps the Vert.x {@link io.vertx.ext.auth.authorization.AuthorizationProvider}
 *       set for compatibility / future adapters but, in v1, evaluates role/scope/permission
 *       requirements directly from the request's {@link dev.vertique.security.authz.AuthorizationClaims}
 *       and does NOT consult the provider set (GitHub issue #165); the default when no app-provided
 *       decision point or policy is bound</li>
 *   <li>{@link SyncPolicyDecisionPoint} — wraps an app-bound sync {@link dev.vertique.security.authz.AuthorizationPolicy};
 *       used when an app provides a sync policy but no async decision point override</li>
 * </ul>
 *
 * <p>Applications that need full async control (remote PDP, database-backed policies) should bind
 * their own {@code AuthorizationDecisionPoint} via Dagger:
 * <pre>{@code
 * @Provides
 * AuthorizationDecisionPoint myDecisionPoint(MyPdpClient client) {
 *     return request -> client.evaluate(request);
 * }
 * }</pre>
 *
 * <p>This is a functional interface and may be implemented as a lambda when the body is simple.
 *
 * @see VertxProviderDecisionPoint
 * @see SyncPolicyDecisionPoint
 * @see SecurityPolicyEnforcer
 */
@FunctionalInterface
public interface AuthorizationDecisionPoint {

    /**
     * Evaluates the given authorization request and returns a decision asynchronously.
     *
     * <p>Implementations are pure evaluators and MUST NOT emit an
     * {@link dev.vertique.security.events.AuthorizationDecisionEvent} — the enforcement layer
     * emits exactly one event per authorization attempt (ADR-0114).
     *
     * @param request the authorization request containing the security context, action, and resource;
     *                must not be {@code null}
     * @return a {@link Future} that completes with the authorization decision; the future may fail
     *         if the underlying evaluation encounters an unrecoverable error
     */
    Future<AuthorizationDecision> decide(AuthorizationRequest request);
}
