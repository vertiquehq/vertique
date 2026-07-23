// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import io.vertx.core.Future;

/**
 * Seam answering whether a {@link DelegationGrant} authorizes a given (actor, subject, scope) triple
 * at evaluation time (PRD identity-002 FR-ID-DG-003).
 *
 * <p>Asynchronous because storage and revocation lookup can require I/O (matching
 * {@code Authorizer}'s contract, NFR-ID2-005): implementations return a {@link Future} and never block
 * the caller. A lookup failure or timeout is <strong>never</strong> a failed {@link Future} — it maps
 * to a fail-closed deny with reason {@link DelegationReasonCodes#GRANT_LOOKUP_FAILED}, so callers can
 * {@code compose}/{@code map} the result without needing a {@code recover()} for the "storage is down"
 * case.
 *
 * <p>Expiry and scope checks ship in the framework's
 * {@link dev.vertique.security.runtime.InMemoryDelegationGrantValidator} default; storage and
 * revocation lookup live behind this seam for consumer-owned durable implementations.
 */
public interface DelegationGrantValidator {

    /**
     * Evaluates whether the grant identified by {@code grantId} currently authorizes {@code actor}
     * acting on behalf of {@code subject} over the scope named by {@code scopeKind}/{@code scopeRef}.
     *
     * @param actor     the principal exercising the grant (the delegated evaluation's actor — the
     *                  grant's {@link DelegationGrant#grantee()})
     * @param subject   the principal the action is on behalf of (the delegated evaluation's subject —
     *                  the grant's {@link DelegationGrant#grantor()})
     * @param scopeKind the namespaced kind of the scope being evaluated
     * @param scopeRef  the scope value within {@code scopeKind}
     * @param grantId   the grant id to evaluate (carried in {@link DelegationContext#authorityId()})
     * @return a future that always succeeds with the {@link DelegationGrantDecision}; never a failed
     *         future — a lookup failure or timeout maps to a fail-closed deny
     */
    Future<DelegationGrantDecision> validate(
            PrincipalRef actor, PrincipalRef subject, String scopeKind, String scopeRef, String grantId);
}
