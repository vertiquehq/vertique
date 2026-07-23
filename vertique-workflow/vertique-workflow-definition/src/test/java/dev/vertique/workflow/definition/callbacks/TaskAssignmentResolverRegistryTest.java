// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.tasks.TaskAssignment;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TaskAssignmentResolverRegistry} and its {@link DefaultTaskAssignmentResolverRegistry} implementation.
 */
class TaskAssignmentResolverRegistryTest {

    record _State(String value) {}

    private static final Function<_State, TaskAssignment> RESOLVER_A =
            s -> new TaskAssignment.User("user-" + s.value());
    private static final Function<_State, TaskAssignment> RESOLVER_B =
            s -> new TaskAssignment.Role("role-" + s.value());

    private static final NamedTaskAssignmentResolver<_State> NAMED_A =
            new NamedTaskAssignmentResolver<>("order.assign", _State.class, RESOLVER_A);

    private TaskAssignmentResolverRegistry registry(TaskAssignmentResolverContributor... contributors) {
        return new DefaultTaskAssignmentResolverRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named resolver by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.assign"))
                    .isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("missing"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("missing")
                    .hasMessageContaining("order.assign");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("task-assignment-resolver");
        }
    }

    @Nested
    @DisplayName("contains and ids")
    class ContainsAndIds {

        @Test
        @DisplayName("ids is empty when no contributors")
        void idsEmpty() {
            assertThat(registry().ids()).isEmpty();
        }

        @Test
        @DisplayName("contains returns true for registered id")
        void containsTrue() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.assign"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("same record twice is idempotent")
        void sameRecordTwiceIsNoOp() {
            assertThat(registry(b -> b.register(NAMED_A), b -> b.register(NAMED_A))
                            .ids())
                    .containsExactly("order.assign");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedTaskAssignmentResolver<_State> conflicting =
                    new NamedTaskAssignmentResolver<>("order.assign", _State.class, RESOLVER_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.assign");
        }
    }

    @Nested
    @DisplayName("NamedTaskAssignmentResolver record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedTaskAssignmentResolver<>(null, _State.class, RESOLVER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedTaskAssignmentResolver<>("  ", _State.class, RESOLVER_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedTaskAssignmentResolver<_State>("id", null, RESOLVER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null resolver throws NullPointerException")
        void nullResolverThrows() {
            assertThatThrownBy(() -> new NamedTaskAssignmentResolver<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
