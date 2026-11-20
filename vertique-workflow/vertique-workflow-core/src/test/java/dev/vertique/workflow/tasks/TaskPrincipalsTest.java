// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.security.PrincipalType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Unit tests for {@link TaskPrincipals} and the matching rule {@link TaskAssignment#matches}. */
class TaskPrincipalsTest {

    private static final TaskPrincipals ALICE = TaskPrincipals.ofUser("alice", Set.of("approver"), Set.of("finance"));

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("ofUser builds a USER principal")
        void ofUserBuildsUser() {
            assertThat(ALICE.userId()).isEqualTo("alice");
            assertThat(ALICE.principalType()).isEqualTo(PrincipalType.USER);
            assertThat(ALICE.roles()).containsExactly("approver");
            assertThat(ALICE.queues()).containsExactly("finance");
        }

        @Test
        @DisplayName("copies the sets so a later change of the input is not seen")
        void copiesSets() {
            Set<String> roles = new HashSet<>(Set.of("a"));
            TaskPrincipals principals = TaskPrincipals.ofUser("u", roles, Set.of());
            roles.add("b");

            assertThat(principals.roles()).containsExactly("a");
            assertThatThrownBy(() -> principals.roles().add("c")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("refuses a null or blank user id, a null type and null sets")
        void refusesInvalidComponents() {
            assertThatNullPointerException().isThrownBy(() -> TaskPrincipals.ofUser(null, Set.of(), Set.of()));
            assertThatIllegalArgumentException().isThrownBy(() -> TaskPrincipals.ofUser(" ", Set.of(), Set.of()));
            assertThatNullPointerException().isThrownBy(() -> new TaskPrincipals("u", null, Set.of(), Set.of()));
            assertThatNullPointerException().isThrownBy(() -> TaskPrincipals.ofUser("u", null, Set.of()));
            assertThatNullPointerException().isThrownBy(() -> TaskPrincipals.ofUser("u", Set.of(), null));
        }

        @Test
        @DisplayName("only a USER principal can match assignments")
        void onlyUserCanMatch() {
            assertThat(ALICE.canMatchAssignments()).isTrue();
            assertThat(new TaskPrincipals("svc", PrincipalType.SERVICE, Set.of(), Set.of()).canMatchAssignments())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("TaskAssignment.matches")
    class Matches {

        @Test
        @DisplayName("a user assignment matches the same user id only")
        void userAssignment() {
            assertThat(new TaskAssignment.User("alice").matches(ALICE)).isTrue();
            assertThat(new TaskAssignment.User("bob").matches(ALICE)).isFalse();
            assertThat(new TaskAssignment.User("Alice").matches(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a role assignment matches one of the caller's roles only")
        void roleAssignment() {
            assertThat(new TaskAssignment.Role("approver").matches(ALICE)).isTrue();
            assertThat(new TaskAssignment.Role("auditor").matches(ALICE)).isFalse();
        }

        @Test
        @DisplayName("a queue assignment matches one of the caller's queues only")
        void queueAssignment() {
            assertThat(new TaskAssignment.Queue("finance").matches(ALICE)).isTrue();
            assertThat(new TaskAssignment.Queue("legal").matches(ALICE)).isFalse();
        }

        @Test
        @DisplayName("kinds do not cross: a role value is not a queue or a user id")
        void kindsDoNotCross() {
            TaskPrincipals principals = TaskPrincipals.ofUser("x", Set.of("shared"), Set.of());

            assertThat(new TaskAssignment.Role("shared").matches(principals)).isTrue();
            assertThat(new TaskAssignment.Queue("shared").matches(principals)).isFalse();
            assertThat(new TaskAssignment.User("shared").matches(principals)).isFalse();
            assertThat(new TaskAssignment.Role("x").matches(principals)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = PrincipalType.class, names = "USER", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("a principal that is not a user matches nothing, whatever it carries")
        void nonUserMatchesNothing(PrincipalType type) {
            TaskPrincipals principals = new TaskPrincipals("alice", type, Set.of("approver"), Set.of("finance"));

            assertThat(new TaskAssignment.User("alice").matches(principals)).isFalse();
            assertThat(new TaskAssignment.Role("approver").matches(principals)).isFalse();
            assertThat(new TaskAssignment.Queue("finance").matches(principals)).isFalse();
        }

        @Test
        @DisplayName("refuses null principals")
        void refusesNull() {
            assertThatNullPointerException().isThrownBy(() -> new TaskAssignment.User("a").matches(null));
        }
    }
}
