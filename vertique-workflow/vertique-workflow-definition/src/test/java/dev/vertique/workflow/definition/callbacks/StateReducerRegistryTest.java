// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link StateReducerRegistry} and its {@link DefaultStateReducerRegistry} implementation.
 */
class StateReducerRegistryTest {

    record _State(String value) {}

    private static final BiFunction<_State, Object, _State> REDUCER_A = (s, e) -> new _State(s.value() + "_a");
    private static final BiFunction<_State, Object, _State> REDUCER_B = (s, e) -> new _State(s.value() + "_b");

    private static final NamedStateReducer<_State> NAMED_A =
            new NamedStateReducer<>("order.reduce", _State.class, REDUCER_A);

    private StateReducerRegistry registry(StateReducerContributor... contributors) {
        return new DefaultStateReducerRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named reducer by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.reduce"))
                    .isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("unknown"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("unknown")
                    .hasMessageContaining("order.reduce");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("state-reducer");
        }
    }

    @Nested
    @DisplayName("contains and ids")
    class ContainsAndIds {

        @Test
        @DisplayName("contains returns true for registered id")
        void containsTrue() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.reduce"))
                    .isTrue();
        }

        @Test
        @DisplayName("contains returns false for unknown id")
        void containsFalse() {
            assertThat(registry(b -> b.register(NAMED_A)).contains("other")).isFalse();
        }

        @Test
        @DisplayName("ids is empty when no contributors")
        void idsEmpty() {
            assertThat(registry().ids()).isEmpty();
        }
    }

    @Nested
    @DisplayName("conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("same record twice is idempotent")
        void sameRecordTwiceIsNoOp() {
            StateReducerRegistry reg = registry(b -> b.register(NAMED_A), b -> b.register(NAMED_A));
            assertThat(reg.ids()).containsExactly("order.reduce");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedStateReducer<_State> conflicting = new NamedStateReducer<>("order.reduce", _State.class, REDUCER_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.reduce");
        }
    }

    @Nested
    @DisplayName("NamedStateReducer record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedStateReducer<>(null, _State.class, REDUCER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedStateReducer<>("  ", _State.class, REDUCER_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedStateReducer<_State>("id", null, REDUCER_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null reducer throws NullPointerException")
        void nullReducerThrows() {
            assertThatThrownBy(() -> new NamedStateReducer<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
