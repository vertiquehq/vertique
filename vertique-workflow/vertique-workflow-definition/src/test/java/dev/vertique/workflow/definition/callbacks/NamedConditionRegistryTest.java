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
 * Verifies {@link NamedConditionRegistry} and its {@link DefaultNamedConditionRegistry} implementation.
 */
class NamedConditionRegistryTest {

    record _State(String value) {}

    private static final Function<_State, Boolean> CONDITION_A = s -> s.value().startsWith("a");
    private static final Function<_State, Boolean> CONDITION_B = s -> s.value().startsWith("b");

    private static final NamedCondition<_State> NAMED_A =
            new NamedCondition<>("order.approved", _State.class, CONDITION_A);

    private NamedConditionRegistry registry(NamedConditionContributor... contributors) {
        return new DefaultNamedConditionRegistry(Set.of(contributors));
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("returns the registered named condition by id")
        void returnsRegisteredEntry() {
            assertThat(registry(b -> b.register(NAMED_A)).lookup("order.approved"))
                    .isEqualTo(NAMED_A);
        }

        @Test
        @DisplayName("throws WorkflowDefinitionException for unknown id")
        void throwsForUnknownId() {
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A)).lookup("missing"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("missing")
                    .hasMessageContaining("order.approved");
        }

        @Test
        @DisplayName("message contains registry role")
        void messageContainsRole() {
            assertThatThrownBy(() -> registry().lookup("any"))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("named-condition");
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
            assertThat(registry(b -> b.register(NAMED_A)).contains("order.approved"))
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
                    .containsExactly("order.approved");
        }

        @Test
        @DisplayName("different records with same id throw IllegalStateException")
        void conflictThrows() {
            NamedCondition<_State> conflicting = new NamedCondition<>("order.approved", _State.class, CONDITION_B);
            assertThatThrownBy(() -> registry(b -> b.register(NAMED_A), b -> b.register(conflicting)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("order.approved");
        }
    }

    @Nested
    @DisplayName("NamedCondition record validation")
    class RecordValidation {

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullIdThrows() {
            assertThatThrownBy(() -> new NamedCondition<>(null, _State.class, CONDITION_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank id throws IllegalArgumentException")
        void blankIdThrows() {
            assertThatThrownBy(() -> new NamedCondition<>("  ", _State.class, CONDITION_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null stateType throws NullPointerException")
        void nullStateTypeThrows() {
            assertThatThrownBy(() -> new NamedCondition<_State>("id", null, CONDITION_A))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null condition throws NullPointerException")
        void nullConditionThrows() {
            assertThatThrownBy(() -> new NamedCondition<>("id", _State.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
