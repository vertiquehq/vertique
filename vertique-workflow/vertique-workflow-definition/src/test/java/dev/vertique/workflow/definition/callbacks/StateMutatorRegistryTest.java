// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link StateMutatorRegistry} and its {@link DefaultStateMutatorRegistry} implementation.
 */
class StateMutatorRegistryTest {

    record _State(String value) {}

    private static final Function<_State, _State> MUTATOR_A = s -> new _State(s.value() + "_a");
    private static final Function<_State, _State> MUTATOR_B = s -> new _State(s.value() + "_b");

    private static final NamedStateMutator<_State> NAMED_A =
            new NamedStateMutator<>("order.mutate", _State.class, MUTATOR_A);

    private StateMutatorRegistry registry(StateMutatorContributor... contributors) {
        return new DefaultStateMutatorRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named mutator by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.mutate"))
                    .isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("unknown"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("unknown")
                    .hasMessageContaining("order.mutate");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("state-mutator");
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
        @DisplayName("contains returns false for unknown id")
        void containsFalse() {
            assertThat(registry().contains("x")).isFalse();
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
                    .containsExactly("order.mutate");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedStateMutator<_State> conflicting = new NamedStateMutator<>("order.mutate", _State.class, MUTATOR_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.mutate");
        }
    }

    @Nested
    @DisplayName("NamedStateMutator record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedStateMutator<>(null, _State.class, MUTATOR_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedStateMutator<>("  ", _State.class, MUTATOR_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedStateMutator<_State>("id", null, MUTATOR_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null mutator throws NullPointerException")
        void nullMutatorThrows() {
            assertThatThrownBy(() -> new NamedStateMutator<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
