// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import io.vertx.core.Future;

/**
 * Application-implemented SPI that re-resolves a principal's <strong>current</strong> authority
 * from its durable key alone (Mode 2, PRD identity-002 §14.3 Phase-2 Appendix).
 *
 * <p>{@link #resolve(PrincipalKey)} receives only {@code (type, id)} — {@link PrincipalKey}
 * structurally carries no attributes, so an implementation cannot resolve scope from
 * request-scoped attributes even by accident; it must consult durable, principal-keyed authority
 * storage (a role/entitlement store, an IdP claim cache, etc.), never the reconstructed context's
 * own attributes.
 *
 * <p><strong>Fails closed.</strong> A missing, malformed, ambiguous, or otherwise unresolvable
 * principal returns a <em>failed</em> {@link Future} — the framework maps any resolver failure or
 * timeout to a deny with reason {@link AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED}. A
 * <em>resolvable</em> principal that currently holds no authority succeeds with
 * {@link AuthorizationClaims#empty()} — a normal downstream deny (no matching claim), distinct
 * from a resolution failure.
 *
 * <p><strong>Failure messages MUST be secret-free.</strong> A store-backed implementation's failure
 * {@link Throwable#getMessage()} MUST NOT carry connection strings, credentials, or other principal
 * attributes — the framework logs failure detail (including the full throwable) at {@code DEBUG}
 * for local diagnosis, and never surfaces it above {@code WARN} with only the principal type and the
 * reason code (CWE-532).
 */
public interface PrincipalAuthorityResolver {

    /**
     * Re-resolves the current authority held by the principal identified by {@code key}.
     *
     * @param key the durable, opaque principal key to resolve; must not be {@code null}
     * @return a future of the principal's current {@link AuthorizationClaims}, succeeding with
     *         {@link AuthorizationClaims#empty()} for a resolvable principal with no current
     *         authority, or failing when the principal cannot be resolved
     */
    Future<AuthorizationClaims> resolve(PrincipalKey key);
}
