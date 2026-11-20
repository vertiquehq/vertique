// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.workflow.tasks.TaskPrincipals;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DefaultTaskPrincipalsMapper} and {@link TaskPrincipalsMapper#orDefault}. */
class DefaultTaskPrincipalsMapperTest {

    private final DefaultTaskPrincipalsMapper mapper = new DefaultTaskPrincipalsMapper();

    private static final AuthorizationClaims CLAIMS = new AuthorizationClaims(
            Set.of(
                    claim(AuthorityKind.ROLE, "approver"),
                    claim(AuthorityKind.ROLE, "auditor"),
                    claim(AuthorityKind.GROUP, "finance"),
                    claim(AuthorityKind.SCOPE, "tasks:read"),
                    claim(AuthorityKind.PERMISSION, "task.complete"),
                    claim(AuthorityKind.ENTITLEMENT, "gold")),
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
    @DisplayName("a user actor: its id and type, ROLE values as roles, GROUP values as queues, nothing else")
    void userActor() {
        SecurityContext context = context(SecurityIdentity.user(principal(PrincipalType.USER, "alice")), CLAIMS);

        TaskPrincipals principals = mapper.toTaskPrincipals(context);

        assertThat(principals)
                .isEqualTo(TaskPrincipals.ofUser("alice", Set.of("approver", "auditor"), Set.of("finance")));
    }

    @Test
    @DisplayName("a service actor keeps its type, so it matches no assignment")
    void serviceActor() {
        SecurityContext context =
                context(SecurityIdentity.service(principal(PrincipalType.SERVICE, "billing")), CLAIMS);

        TaskPrincipals principals = mapper.toTaskPrincipals(context);

        assertThat(principals.userId()).isEqualTo("billing");
        assertThat(principals.principalType()).isEqualTo(PrincipalType.SERVICE);
        assertThat(principals.canMatchAssignments()).isFalse();
    }

    @Test
    @DisplayName("an anonymous caller keeps its type and matches no assignment")
    void anonymous() {
        SecurityContext context = context(SecurityIdentity.anonymous(), AuthorizationClaims.empty());

        TaskPrincipals principals = mapper.toTaskPrincipals(context);

        assertThat(principals.principalType()).isEqualTo(PrincipalType.ANONYMOUS);
        assertThat(principals.canMatchAssignments()).isFalse();
    }

    @Test
    @DisplayName("a delegated subject: the subject's id and type, and none of the actor's roles or groups")
    void delegatedSubject() {
        SecurityIdentity identity = new SecurityIdentity(
                principal(PrincipalType.SERVICE, "portal"),
                Optional.of(principal(PrincipalType.USER, "alice")),
                Optional.empty(),
                Optional.empty());

        TaskPrincipals principals = mapper.toTaskPrincipals(context(identity, CLAIMS));

        assertThat(principals).isEqualTo(TaskPrincipals.ofUser("alice", Set.of(), Set.of()));
    }

    @Test
    @DisplayName("a subject that is the actor itself keeps the claims")
    void subjectSameAsActor() {
        PrincipalRef alice = principal(PrincipalType.USER, "alice");
        SecurityIdentity identity = new SecurityIdentity(alice, Optional.of(alice), Optional.empty(), Optional.empty());

        TaskPrincipals principals = mapper.toTaskPrincipals(context(identity, CLAIMS));

        assertThat(principals.roles()).containsExactlyInAnyOrder("approver", "auditor");
        assertThat(principals.queues()).containsExactly("finance");
    }

    @Test
    @DisplayName("refuses a null context")
    void refusesNull() {
        assertThatNullPointerException().isThrownBy(() -> mapper.toTaskPrincipals(null));
    }

    @Test
    @DisplayName("orDefault returns the application's mapper when bound, the default otherwise")
    void orDefault() {
        TaskPrincipalsMapper custom = context -> TaskPrincipals.ofUser("x", Set.of(), Set.of());

        assertThat(TaskPrincipalsMapper.orDefault(Optional.of(custom))).isSameAs(custom);
        assertThat(TaskPrincipalsMapper.orDefault(Optional.empty())).isInstanceOf(DefaultTaskPrincipalsMapper.class);
        assertThatNullPointerException().isThrownBy(() -> TaskPrincipalsMapper.orDefault(null));
    }
}
