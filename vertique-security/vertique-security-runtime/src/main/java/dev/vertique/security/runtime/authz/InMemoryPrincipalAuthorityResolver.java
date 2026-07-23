// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import dev.vertique.security.authz.PrincipalKey;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-shipped, in-memory {@link PrincipalAuthorityResolver} reference implementation (PRD
 * identity-002 §14.3 Phase-2 Appendix, Mode 2).
 *
 * <p>Holds a fixed, immutable map of seeded {@link PrincipalKey} → {@link AuthorizationClaims}
 * pairs supplied at construction time — the same dependency-clean-default shape as
 * {@code InMemoryDelegationGrantValidator} / {@code InMemoryPolicyDefinitionSource} — and a
 * separate, fixed set of keys deliberately seeded as <strong>ambiguous</strong> (e.g. a principal
 * whose durable identity could not be uniquely resolved by the backing store this reference
 * implementation stands in for). {@link #resolve(PrincipalKey)} consults only the given
 * {@code (type, id)} key: {@link PrincipalKey} structurally carries no attributes accessor, so
 * there is nothing else for this resolver to read.
 *
 * <p><strong>Fails closed.</strong> A key present in the ambiguous set, or absent from the seeded
 * claims map entirely, resolves to a <em>failed</em> {@link Future} — mirroring
 * {@link PrincipalAuthorityResolver}'s contract that a missing, malformed, ambiguous, or otherwise
 * unresolvable principal never succeeds. A seeded key with no claims resolves normally to
 * {@link AuthorizationClaims#empty()} (a resolvable principal with no current authority), never a
 * failure. Durable, principal-keyed authority storage is a consumer-owned implementation of the
 * same {@link PrincipalAuthorityResolver} seam.
 */
public final class InMemoryPrincipalAuthorityResolver implements PrincipalAuthorityResolver {

    private final Map<PrincipalKey, AuthorizationClaims> claimsByPrincipal;
    private final Set<PrincipalKey> ambiguousPrincipals;

    /**
     * Creates a resolver over the given seeded claims and ambiguous-principal set.
     *
     * @param claimsByPrincipal   the seeded {@code (type, id)} → current-authority claims this
     *                            resolver serves; must not be {@code null} or contain {@code null}
     *                            keys or values
     * @param ambiguousPrincipals the keys this resolver treats as ambiguous and therefore always
     *                            fails closed for, even if also present in
     *                            {@code claimsByPrincipal}; must not be {@code null}
     */
    @Inject
    public InMemoryPrincipalAuthorityResolver(
            Map<PrincipalKey, AuthorizationClaims> claimsByPrincipal, Set<PrincipalKey> ambiguousPrincipals) {
        Objects.requireNonNull(claimsByPrincipal, "claimsByPrincipal");
        Objects.requireNonNull(ambiguousPrincipals, "ambiguousPrincipals");
        this.claimsByPrincipal = Map.copyOf(claimsByPrincipal);
        this.ambiguousPrincipals = Set.copyOf(ambiguousPrincipals);
    }

    /**
     * Creates a resolver over the given seeded claims with no ambiguous principals.
     *
     * @param claimsByPrincipal the seeded {@code (type, id)} → current-authority claims this
     *                          resolver serves; must not be {@code null} or contain {@code null}
     *                          keys or values
     */
    public InMemoryPrincipalAuthorityResolver(Map<PrincipalKey, AuthorizationClaims> claimsByPrincipal) {
        this(claimsByPrincipal, Set.of());
    }

    @Override
    public Future<AuthorizationClaims> resolve(PrincipalKey key) {
        Objects.requireNonNull(key, "key");
        if (ambiguousPrincipals.contains(key)) {
            return Future.failedFuture("principal " + key + " is ambiguous — cannot resolve current authority");
        }
        AuthorizationClaims claims = claimsByPrincipal.get(key);
        if (claims == null) {
            return Future.failedFuture("principal " + key + " is unknown — cannot resolve current authority");
        }
        return Future.succeededFuture(claims);
    }
}
