// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityContexts}.
 *
 * <p>Verifies the transport-neutral static assembly contract (identity-002 §14.3, Amendment A2): a
 * {@code system(...)} preset that carries a preassembled service identity as-is, an
 * {@code assemble(...)} path used outside any REST middleware, and an {@code unauthenticated(...)}
 * preset carrying {@code none()} authentication with no origin.
 *
 * <p>Because {@link SecurityContexts} is a plain static utility class with no injected
 * collaborator, it cannot invoke a {@link dev.vertique.security.events.SecurityEventObserver} by
 * construction — the CA-007 "emits no events" guarantee is structural here, not something a test
 * needs to exercise via a counting observer (contrast with the injected-emitter design the prior
 * {@code DefaultSecurityContextFactory} required).
 */
class SecurityContextsTest {

    @Test
    @DisplayName("system(serviceIdentity) carries the given service identity as-is")
    void systemPresetCarriesServiceIdentity() {
        SecurityIdentity serviceIdentity = SystemIdentities.scheduledJob("job-x");

        SecurityContext ctx = SecurityContexts.system(serviceIdentity);

        assertEquals(serviceIdentity, ctx.identity());
    }

    @Test
    @DisplayName("system(...) rejects a non-SYSTEM actor")
    void systemRejectsNonSystemActor() {
        SecurityIdentity userIdentity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SecurityContexts.system(userIdentity));
        assertTrue(ex.getMessage().contains("USER"), "the rejection message must name the offending actor type");
    }

    @Test
    @DisplayName("system(...) rejects a SYSTEM actor carrying a subject")
    void systemRejectsSubjectBearingIdentity() {
        PrincipalRef systemActor = SystemIdentities.scheduledJob("job-x").actor();
        SecurityIdentity subjectBearing = new SecurityIdentity(
                systemActor,
                Optional.of(new PrincipalRef(PrincipalType.USER, "u1", Map.of())),
                Optional.empty(),
                Optional.empty());

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SecurityContexts.system(subjectBearing));
        assertTrue(ex.getMessage().contains("actor-only"), "the rejection message must state the actor-only contract");
    }

    @Test
    @DisplayName("system(...) rejects a SYSTEM actor carrying a client")
    void systemRejectsClientBearingIdentity() {
        PrincipalRef systemActor = SystemIdentities.scheduledJob("job-x").actor();
        SecurityIdentity clientBearing = new SecurityIdentity(
                systemActor,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ClientRef("client-1", "jwt-azp", Map.of())));

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SecurityContexts.system(clientBearing));
        assertTrue(ex.getMessage().contains("actor-only"), "the rejection message must state the actor-only contract");
    }

    @Test
    @DisplayName("system(...) rejects a SYSTEM actor carrying a delegation")
    void systemRejectsDelegationBearingIdentity() {
        PrincipalRef systemActor = SystemIdentities.scheduledJob("job-x").actor();
        SecurityIdentity delegationBearing = new SecurityIdentity(
                systemActor,
                Optional.empty(),
                Optional.of(new DelegationContext("impersonation", "policy-1", Optional.empty(), Map.of())),
                Optional.empty());

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SecurityContexts.system(delegationBearing));
        assertTrue(ex.getMessage().contains("actor-only"), "the rejection message must state the actor-only contract");
    }

    @Test
    @DisplayName("assemble(identity, auth, claims, origin) builds a usable context outside REST")
    void assembleBuildsContextOutsideRest() {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.custom("password"), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = new AuthorizationClaims(
                Set.of(new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "test", Map.of())), Map.of());
        RequestOrigin origin = new RequestOrigin(
                "10.0.0.1", 443, List.of(), 0, false, "10.0.0.1", "https", "example.com", Optional.empty());

        SecurityContext ctx = SecurityContexts.assemble(identity, auth, claims, Optional.of(origin));

        assertNotNull(ctx);
        assertEquals(identity, ctx.identity());
        assertEquals(auth, ctx.authentication());
        assertEquals(claims, ctx.authorization());
        assertTrue(ctx.origin().isPresent());
        assertEquals(origin, ctx.origin().get());
    }

    @Test
    @DisplayName("unauthenticated(identity) carries the given identity with none() authentication")
    void unauthenticatedCarriesNoneAuthAndGivenIdentity() {
        SecurityIdentity identity = SystemIdentities.scheduledJob("job-x");

        SecurityContext ctx = SecurityContexts.unauthenticated(identity);

        assertSame(identity, ctx.identity());
        assertEquals(AuthMethodKind.NONE, ctx.authentication().primaryMethod().normalizedKind());
        assertTrue(ctx.authentication().evidence().isEmpty());
        assertTrue(ctx.authorization().claims().isEmpty());
        assertEquals(Optional.empty(), ctx.origin());
    }
}
