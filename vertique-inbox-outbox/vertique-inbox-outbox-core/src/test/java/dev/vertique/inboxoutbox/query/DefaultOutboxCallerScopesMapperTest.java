// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DefaultOutboxCallerScopesMapper}, {@link OutboxCallerScopes} and the optional binding. */
class DefaultOutboxCallerScopesMapperTest {

    private final DefaultOutboxCallerScopesMapper mapper = new DefaultOutboxCallerScopesMapper();

    private static final AuthorizationClaims CLAIMS = new AuthorizationClaims(
            Set.of(
                    claim(AuthorityKind.ENTITLEMENT, "acme"),
                    claim(AuthorityKind.ENTITLEMENT, "default"),
                    claim(AuthorityKind.ROLE, "other-scope"),
                    claim(AuthorityKind.GROUP, "other-scope"),
                    claim(AuthorityKind.SCOPE, "other-scope"),
                    claim(AuthorityKind.PERMISSION, "other-scope")),
            Map.of());

    private static AuthorityClaim claim(AuthorityKind kind, String value) {
        return new AuthorityClaim(kind, value, "issuer", "audience", "test", Map.of());
    }

    private static PrincipalRef principal(PrincipalType type, String id) {
        return new PrincipalRef(type, id, Map.of());
    }

    private static SecurityContext context(SecurityIdentity identity, AuthorizationClaims claims) {
        SecurityContext context = mock(SecurityContext.class);
        when(context.identity()).thenReturn(identity);
        when(context.authorization()).thenReturn(claims);
        return context;
    }

    @Test
    @DisplayName("a user actor: its entitlements are its scope keys, and no other authority kind contributes")
    void userActor() {
        OutboxCallerScopes scopes =
                mapper.toCallerScopes(context(SecurityIdentity.user(principal(PrincipalType.USER, "alice")), CLAIMS));

        assertEquals(OutboxCallerScopes.ofUser(Set.of("acme", "default")), scopes);
        assertTrue(scopes.permits("acme"));
        assertFalse(scopes.permits("other-scope"));
    }

    @Test
    @DisplayName("a user with no entitlement sees no entry")
    void noEntitlement() {
        OutboxCallerScopes scopes = mapper.toCallerScopes(
                context(SecurityIdentity.user(principal(PrincipalType.USER, "alice")), AuthorizationClaims.empty()));

        assertFalse(scopes.canMatchEntries());
        assertFalse(scopes.permits("default"));
    }

    @Test
    @DisplayName("a service actor keeps its type and matches no entry, whatever it carries")
    void serviceActor() {
        OutboxCallerScopes scopes = mapper.toCallerScopes(
                context(SecurityIdentity.service(principal(PrincipalType.SERVICE, "billing")), CLAIMS));

        assertEquals(PrincipalType.SERVICE, scopes.principalType());
        assertFalse(scopes.canMatchEntries());
        assertFalse(scopes.permits("acme"));
    }

    @Test
    @DisplayName("an anonymous caller matches no entry")
    void anonymous() {
        OutboxCallerScopes scopes =
                mapper.toCallerScopes(context(SecurityIdentity.anonymous(), AuthorizationClaims.empty()));

        assertEquals(PrincipalType.ANONYMOUS, scopes.principalType());
        assertFalse(scopes.canMatchEntries());
    }

    @Test
    @DisplayName("a delegated subject: its type, and none of the actor's entitlements")
    void delegatedSubject() {
        SecurityIdentity identity = new SecurityIdentity(
                principal(PrincipalType.SERVICE, "portal"),
                Optional.of(principal(PrincipalType.USER, "alice")),
                Optional.empty(),
                Optional.empty());

        OutboxCallerScopes scopes = mapper.toCallerScopes(context(identity, CLAIMS));

        assertEquals(OutboxCallerScopes.ofUser(Set.of()), scopes);
        assertFalse(scopes.permits("acme"));
    }

    @Test
    @DisplayName("a subject that is the actor itself keeps the entitlements")
    void subjectSameAsActor() {
        PrincipalRef alice = principal(PrincipalType.USER, "alice");
        SecurityIdentity identity = new SecurityIdentity(alice, Optional.of(alice), Optional.empty(), Optional.empty());

        assertEquals(
                Set.of("acme", "default"),
                mapper.toCallerScopes(context(identity, CLAIMS)).scopeKeys());
    }

    @Test
    @DisplayName("the scopes copy their set and refuse null components")
    void recordRules() {
        java.util.HashSet<String> keys = new java.util.HashSet<>(Set.of("a"));
        OutboxCallerScopes scopes = OutboxCallerScopes.ofUser(keys);
        keys.add("b");

        assertEquals(Set.of("a"), scopes.scopeKeys());
        assertThrows(NullPointerException.class, () -> new OutboxCallerScopes(null, Set.of()));
        assertThrows(NullPointerException.class, () -> new OutboxCallerScopes(PrincipalType.USER, null));
        assertThrows(
                UnsupportedOperationException.class, () -> scopes.scopeKeys().add("c"));
    }

    @Test
    @DisplayName("refuses a null context")
    void refusesNull() {
        assertThrows(NullPointerException.class, () -> mapper.toCallerScopes(null));
    }

    @Test
    @DisplayName("orDefault returns the application's mapper when bound, the default otherwise")
    void orDefault() {
        OutboxCallerScopesMapper custom = context -> OutboxCallerScopes.ofUser(Set.of("x"));

        assertSame(custom, OutboxCallerScopesMapper.orDefault(Optional.of(custom)));
        assertInstanceOf(DefaultOutboxCallerScopesMapper.class, OutboxCallerScopesMapper.orDefault(Optional.empty()));
        assertThrows(NullPointerException.class, () -> OutboxCallerScopesMapper.orDefault(null));
    }
}
