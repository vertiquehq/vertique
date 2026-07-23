// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.BranchResult;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link BranchResultReducerRegistry} and its {@link DefaultBranchResultReducerRegistry} implementation.
 */
class BranchResultReducerRegistryTest {

    record _State(String value) {}

    private static final BiFunction<_State, Map<String, BranchResult>, _State> REDUCER_A =
            (s, results) -> new _State(s.value() + "_a");
    private static final BiFunction<_State, Map<String, BranchResult>, _State> REDUCER_B =
            (s, results) -> new _State(s.value() + "_b");

    private static final NamedBranchResultReducer<_State> NAMED_A =
            new NamedBranchResultReducer<>("order.branch.reduce", _State.class, REDUCER_A);

    private BranchResultReducerRegistry registry(BranchResultReducerContributor... contributors) {
        return new DefaultBranchResultReducerRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named reducer by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.branch.reduce"))
                    .isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("missing"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("missing")
                    .hasMessageContaining("order.branch.reduce");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("branch-result-reducer");
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
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.branch.reduce"))
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
                    .containsExactly("order.branch.reduce");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedBranchResultReducer<_State> conflicting =
                    new NamedBranchResultReducer<>("order.branch.reduce", _State.class, REDUCER_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.branch.reduce");
        }
    }

    @Nested
    @DisplayName("NamedBranchResultReducer record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedBranchResultReducer<>(null, _State.class, REDUCER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedBranchResultReducer<>("  ", _State.class, REDUCER_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedBranchResultReducer<_State>("id", null, REDUCER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null reducer throws NullPointerException")
        void nullReducerThrows() {
            assertThatThrownBy(() -> new NamedBranchResultReducer<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
