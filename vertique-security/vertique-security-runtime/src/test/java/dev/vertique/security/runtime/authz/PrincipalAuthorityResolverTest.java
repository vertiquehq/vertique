// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.PrincipalType;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.PrincipalKey;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryPrincipalAuthorityResolver} — the framework-shipped, in-memory
 * {@link dev.vertique.security.authz.PrincipalAuthorityResolver} reference implementation (PRD
 * identity-002 §14.3 Phase-2 Appendix). Verifies resolution keys strictly off {@code (type, id)}
 * and fails closed for both a missing and an ambiguous principal.
 */
class PrincipalAuthorityResolverTest {

    private static final PrincipalKey KNOWN = new PrincipalKey(PrincipalType.USER, "user-1");
    private static final PrincipalKey AMBIGUOUS = new PrincipalKey(PrincipalType.USER, "user-ambiguous");
    private static final PrincipalKey MISSING = new PrincipalKey(PrincipalType.USER, "user-missing");

    private static final AuthorizationClaims CLAIMS = new AuthorizationClaims(
            Set.of(new AuthorityClaim(AuthorityKind.ROLE, "admin", "idp", "aud", "resolved", Map.of())), Map.of());

    @Test
    @DisplayName("resolve(key) for a seeded key returns its claims from (type, id) alone")
    void resolvesFromTypeAndIdOnly() {
        InMemoryPrincipalAuthorityResolver resolver =
                new InMemoryPrincipalAuthorityResolver(Map.of(KNOWN, CLAIMS), Set.of());

        Future<AuthorizationClaims> future = resolver.resolve(KNOWN);

        assertTrue(future.succeeded());
        assertEquals(CLAIMS, future.result());
    }

    @Test
    @DisplayName("resolve(key) never reads attributes — repeated resolves of the same key are identical, and "
            + "PrincipalKey structurally carries no attributes accessor to read")
    void neverReadsAttributes() {
        InMemoryPrincipalAuthorityResolver resolver =
                new InMemoryPrincipalAuthorityResolver(Map.of(KNOWN, CLAIMS), Set.of());

        Future<AuthorizationClaims> first = resolver.resolve(KNOWN);
        Future<AuthorizationClaims> second = resolver.resolve(KNOWN);

        assertEquals(first.result(), second.result());
        // PrincipalKey has exactly two components — (type, id) — and no attributes accessor at
        // all, so there is structurally nothing for a resolver keyed on it to read beyond that
        // durable tuple.
        assertEquals(2, PrincipalKey.class.getRecordComponents().length);
    }

    @Test
    @DisplayName("resolve(key) for an unseeded (missing) key fails closed")
    void missingPrincipalFailsClosed() {
        InMemoryPrincipalAuthorityResolver resolver =
                new InMemoryPrincipalAuthorityResolver(Map.of(KNOWN, CLAIMS), Set.of());

        Future<AuthorizationClaims> future = resolver.resolve(MISSING);

        assertTrue(future.failed());
    }

    @Test
    @DisplayName("resolve(key) for a seeded-ambiguous key fails closed")
    void ambiguousFailsClosed() {
        InMemoryPrincipalAuthorityResolver resolver =
                new InMemoryPrincipalAuthorityResolver(Map.of(KNOWN, CLAIMS), Set.of(AMBIGUOUS));

        Future<AuthorizationClaims> future = resolver.resolve(AMBIGUOUS);

        assertTrue(future.failed());
    }
}
